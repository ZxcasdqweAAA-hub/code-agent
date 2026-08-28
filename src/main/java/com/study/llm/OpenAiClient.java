package com.study.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.config.ProviderConfig;
import com.study.conversation.Message;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class OpenAiClient implements LlmClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProviderConfig config;
    private final String systemPrompt;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public OpenAiClient(ProviderConfig config, String systemPrompt) {
        this.config = config;
        this.systemPrompt = systemPrompt;

    }

    @Override
    public LlmStream stream(Request request) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint())
                    .timeout(Duration.ofMinutes(5))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(request)))
                    .build();
            return HttpSseStream.start(httpClient, httpRequest, new OpenAiLineHandler(), "OpenAI 请求失败", this::redact);
        } catch (Exception e) {
            return FailedLlmStream.of("OpenAI 请求失败: " + e.getMessage());
        }
    }

    @Override
    public String name() {
        return config.getName();
    }

    @Override
    public String model() {
        return config.getModel();
    }

    private URI endpoint() {
        String base = config.getBaseUrl();
        if (base == null || base.isBlank()) {
            base = "https://api.openai.com/v1";
        }
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (normalized.endsWith("/chat/completions")) {
            return URI.create(normalized);
        }
        if (normalized.endsWith("/v1")) {
            return URI.create(normalized + "/chat/completions");
        }
        return URI.create(normalized + "/v1/chat/completions");
    }

    private String requestBody(Request req) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        body.put("messages", openAiMessages(req));
        List<Map<String, Object>> tools = req.tools();
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", openAiTools(tools));
            body.put("tool_choice", "auto");
        }
        return JSON.writeValueAsString(body);
    }

    private List<Map<String, Object>> openAiMessages(Request req) {
        List<Map<String, Object>> converted = new ArrayList<>();
        converted.add(Map.of("role", "system", "content", openAiSystem(req.system())));
        for (Message message : req.messages()) {
            if ("tool".equals(message.role())) {
                for (ToolResult result : message.toolResults()) {
                    converted.add(Map.of(
                            "role", "tool",
                            "tool_call_id", result.toolCallId(),
                            "content", result.content()));
                }
            } else if ("assistant".equals(message.role()) && !message.toolCalls().isEmpty()) {
                Map<String, Object> assistant = new LinkedHashMap<>();
                assistant.put("role", "assistant");
                assistant.put("content", message.content().isBlank() ? null : message.content());
                assistant.put("tool_calls", message.toolCalls().stream().map(call -> {
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", call.name());
                    fn.put("arguments", call.arguments());
                    Map<String, Object> tc = new LinkedHashMap<>();
                    tc.put("id", call.id());
                    tc.put("type", "function");
                    tc.put("function", fn);
                    return tc;
                }).toList());
                converted.add(assistant);
            } else {
                converted.add(Map.of("role", message.role(), "content", message.content()));
            }
        }
        if (!req.reminder().isBlank()) {
            converted.add(Map.of("role", "user", "content", req.reminder()));
        }
        return converted;
    }

    private String openAiSystem(LlmSystem system) {
        String stable = !system.stable().isBlank() ? system.stable() : systemPrompt;
        if (system.environment().isBlank()) {
            return stable;
        }
        return stable + "\n\n" + system.environment();
    }

    private List<Map<String, Object>> openAiTools(List<Map<String, Object>> tools) {
        return tools.stream().map(tool -> {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.get("name"));
            function.put("description", tool.getOrDefault("description", ""));
            function.put("parameters", tool.getOrDefault("parameters", tool.get("input_schema")));
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("type", "function");
            wrapper.put("function", function);
            return wrapper;
        }).toList();
    }

    static Usage usageFrom(JsonNode usage) {
        long cacheRead = usage.path("prompt_cache_hit_tokens").asLong(-1);
        if (cacheRead < 0) {
            cacheRead = usage.path("prompt_tokens_details").path("cached_tokens").asLong(0);
        }
        long input = usage.path("prompt_tokens").asLong(0);
        return new Usage(input, usage.path("completion_tokens").asLong(0), 0, cacheRead, input);
    }

    private String normalizeJson(String arguments) {
        return arguments == null || arguments.isBlank() ? "{}" : arguments;
    }

    private String redact(String body) {
        if (body == null) {
            return "";
        }
        return body.replace(config.getApiKey(), "[redacted]");
    }

    private static final class ToolCallBuilder {
        private String id = "tool-call";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();
    }

    private final class OpenAiLineHandler implements HttpSseStream.LineHandler {
        private final Map<Integer, ToolCallBuilder> toolBuilders = new LinkedHashMap<>();
        private boolean toolsCompleted;

        @Override
        public void onLine(String line, Consumer<StreamEvent> emit) throws Exception {
            if (!line.startsWith("data:")) {
                return;
            }
            String data = line.substring("data:".length()).trim();
            if (data.isEmpty()) {
                return;
            }
            if ("[DONE]".equals(data)) {
                completeTools(emit);
                emit.accept(new StreamEvent.StreamEnd("stop", 0, 0));
                return;
            }
            JsonNode root = JSON.readTree(data);
            JsonNode usage = root.path("usage");
            if (usage.isObject() && !usage.isNull()) {
                emit.accept(new StreamEvent.UsageEvent(usageFrom(usage)));
            }
            JsonNode delta = root.path("choices").path(0).path("delta");
            JsonNode content = delta.path("content");
            if (content.isTextual()) {
                emit.accept(new StreamEvent.TextDelta(content.asText()));
            }
            JsonNode toolCalls = delta.path("tool_calls");
            if (!toolCalls.isArray()) {
                return;
            }
            for (JsonNode toolCall : toolCalls) {
                int index = toolCall.path("index").asInt(0);
                ToolCallBuilder builder = toolBuilders.computeIfAbsent(index, ignored -> new ToolCallBuilder());
                if (toolCall.path("id").isTextual()) {
                    builder.id = toolCall.path("id").asText();
                }
                JsonNode function = toolCall.path("function");
                if (function.path("name").isTextual()) {
                    builder.name = function.path("name").asText();
                }
                if (function.path("arguments").isTextual()) {
                    String arguments = function.path("arguments").asText();
                    builder.arguments.append(arguments);
                    emit.accept(new StreamEvent.ToolCallDelta(builder.id, builder.name, arguments));
                }
            }
        }

        @Override
        public void onEnd(Consumer<StreamEvent> emit) {
            completeTools(emit);
        }

        private void completeTools(Consumer<StreamEvent> emit) {
            if (toolsCompleted) {
                return;
            }
            toolsCompleted = true;
            for (ToolCallBuilder builder : toolBuilders.values()) {
                if (builder.name != null && !builder.name.isBlank()) {
                    emit.accept(new StreamEvent.ToolCallComplete(
                            builder.id, builder.name, normalizeJson(builder.arguments.toString())));
                }
            }
        }
    }
}
