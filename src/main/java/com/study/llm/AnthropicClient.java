package com.study.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.config.ProviderConfig;
import com.study.conversation.Message;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class AnthropicClient implements LlmClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProviderConfig config;
    private final String systemPrompt;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public AnthropicClient(ProviderConfig config, String systemPrompt) {
        this.config = config;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public BlockingQueue<StreamEvent> stream(Request request) {
        BlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
        Thread.ofVirtual().name("anthropic-stream").start(() -> streamIntoQueue(request, queue));
        return queue;
    }

    @Override
    public String name() {
        return config.getName();
    }

    @Override
    public String model() {
        return config.getModel();
    }

    private void streamIntoQueue(Request req, BlockingQueue<StreamEvent> queue) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint())
                    .timeout(Duration.ofMinutes(5))
                    .header("x-api-key", config.getApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(req)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                queue.add(new StreamEvent.Error("Anthropic 请求失败，HTTP " + response.statusCode() + ": " + redact(response.body())));
                return;
            }
            readSse(response.body(), queue);
            queue.add(new StreamEvent.StreamEnd("stop", 0, 0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            queue.add(new StreamEvent.Error("Anthropic 请求已中断"));
        } catch (Exception e) {
            queue.add(new StreamEvent.Error("Anthropic 请求失败: " + e.getMessage()));
        }
    }

    private URI endpoint() {
        String base = config.getBaseUrl();
        if (base == null || base.isBlank()) {
            base = "https://api.anthropic.com";
        }
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (normalized.endsWith("/messages")) {
            return URI.create(normalized);
        }
        if (normalized.endsWith("/v1")) {
            return URI.create(normalized + "/messages");
        }
        return URI.create(normalized + "/v1/messages");
    }

    private String requestBody(Request req) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("system", anthropicSystem(req.system()));
        body.put("max_tokens", 4096);
        body.put("stream", true);
        body.put("messages", anthropicMessages(req.messages(), req.reminder()));
        List<Map<String, Object>> tools = req.tools();
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", anthropicTools(tools));
        }
        if (config.isThinking() && (tools == null || tools.isEmpty())) {
            body.put("thinking", Map.of("type", "enabled", "budget_tokens", 1024));
        }
        return JSON.writeValueAsString(body);
    }

    private List<Map<String, Object>> anthropicSystem(LlmSystem system) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        String stable = !system.stable().isBlank() ? system.stable() : systemPrompt;
        if (!stable.isBlank()) {
            blocks.add(Map.of(
                    "type", "text",
                    "text", stable,
                    "cache_control", Map.of("type", "ephemeral")));
        }
        if (!system.environment().isBlank()) {
            blocks.add(Map.of("type", "text", "text", system.environment()));
        }
        return blocks;
    }

    private List<Map<String, Object>> anthropicMessages(List<Message> messages, String reminder) {
        List<Map<String, Object>> converted = new ArrayList<>();
        for (Message message : messages) {
            if ("tool".equals(message.role())) {
                List<Map<String, Object>> blocks = new ArrayList<>();
                for (ToolResult result : message.toolResults()) {
                    Map<String, Object> block = new LinkedHashMap<>();
                    block.put("type", "tool_result");
                    block.put("tool_use_id", result.toolCallId());
                    block.put("content", result.content());
                    block.put("is_error", result.error());
                    blocks.add(block);
                }
                converted.add(Map.of("role", "user", "content", blocks));
            } else if ("assistant".equals(message.role()) && !message.toolCalls().isEmpty()) {
                List<Map<String, Object>> blocks = new ArrayList<>();
                if (!message.content().isBlank()) {
                    blocks.add(Map.of("type", "text", "text", message.content()));
                }
                for (ToolCall call : message.toolCalls()) {
                    Map<String, Object> block = new LinkedHashMap<>();
                    block.put("type", "tool_use");
                    block.put("id", call.id());
                    block.put("name", call.name());
                    block.put("input", parseJsonObject(call.arguments()));
                    blocks.add(block);
                }
                converted.add(Map.of("role", "assistant", "content", blocks));
            } else {
                converted.add(Map.of("role", message.role(), "content", message.content()));
            }
        }
        appendReminder(converted, reminder);
        return converted;
    }

    private void appendReminder(List<Map<String, Object>> messages, String reminder) {
        if (reminder == null || reminder.isBlank()) {
            return;
        }
        Map<String, Object> block = Map.of("type", "text", "text", reminder);
        if (messages.isEmpty() || !"user".equals(messages.get(messages.size() - 1).get("role"))) {
            messages.add(Map.of("role", "user", "content", List.of(block)));
            return;
        }
        Map<String, Object> last = new LinkedHashMap<>(messages.remove(messages.size() - 1));
        Object content = last.get("content");
        List<Object> blocks = new ArrayList<>();
        if (content instanceof List<?> list) {
            blocks.addAll(list);
        } else if (content instanceof String text && !text.isBlank()) {
            blocks.add(Map.of("type", "text", "text", text));
        }
        blocks.add(block);
        last.put("content", blocks);
        messages.add(last);
    }

    private List<Map<String, Object>> anthropicTools(List<Map<String, Object>> tools) {
        return tools.stream().map(tool -> {
            Map<String, Object> converted = new LinkedHashMap<>();
            converted.put("name", tool.get("name"));
            converted.put("description", tool.getOrDefault("description", ""));
            converted.put("input_schema", tool.getOrDefault("input_schema", tool.get("parameters")));
            return converted;
        }).toList();
    }

    private void readSse(String body, BlockingQueue<StreamEvent> queue) throws IOException {
        Map<Integer, ToolCallBuilder> toolBuilders = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(body))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                if (data.isEmpty()) {
                    continue;
                }
                JsonNode root = JSON.readTree(data);
                String type = root.path("type").asText("");
                if ("content_block_start".equals(type)) {
                    JsonNode block = root.path("content_block");
                    if ("tool_use".equals(block.path("type").asText(""))) {
                        int index = root.path("index").asInt(0);
                        ToolCallBuilder builder = toolBuilders.computeIfAbsent(index, ignored -> new ToolCallBuilder());
                        builder.id = block.path("id").asText(builder.id);
                        builder.name = block.path("name").asText(builder.name);
                    }
                }
                if ("content_block_delta".equals(type)) {
                    int index = root.path("index").asInt(0);
                    JsonNode delta = root.path("delta");
                    String deltaType = delta.path("type").asText("");
                    if ("text_delta".equals(deltaType) && delta.path("text").isTextual()) {
                        queue.add(new StreamEvent.TextDelta(delta.path("text").asText()));
                    } else if ("input_json_delta".equals(deltaType) && delta.path("partial_json").isTextual()) {
                        ToolCallBuilder builder = toolBuilders.computeIfAbsent(index, ignored -> new ToolCallBuilder());
                        String json = delta.path("partial_json").asText();
                        builder.arguments.append(json);
                        queue.add(new StreamEvent.ToolCallDelta(builder.id, builder.name, json));
                    } else if (delta.path("thinking").isTextual()) {
                        queue.add(new StreamEvent.ThinkingDelta(delta.path("thinking").asText()));
                    }
                }
                if ("message_delta".equals(type)) {
                    JsonNode usage = root.path("usage");
                    if (usage.isObject()) {
                        queue.add(new StreamEvent.UsageEvent(new Usage(
                                usage.path("input_tokens").asLong(0),
                                usage.path("output_tokens").asLong(0),
                                usage.path("cache_creation_input_tokens").asLong(0),
                                usage.path("cache_read_input_tokens").asLong(0))));
                    }
                }
            }
        }
        for (ToolCallBuilder builder : toolBuilders.values()) {
            if (builder.name != null && !builder.name.isBlank()) {
                queue.add(new StreamEvent.ToolCallComplete(builder.id, builder.name, normalizeJson(builder.arguments.toString())));
            }
        }
    }

    private Object parseJsonObject(String json) {
        try {
            return JSON.readValue(normalizeJson(json), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
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
}
