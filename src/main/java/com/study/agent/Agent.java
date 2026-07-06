package com.study.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.conversation.ConversationManager;
import com.study.llm.LlmClient;
import com.study.llm.StreamEvent;
import com.study.llm.ToolCall;
import com.study.llm.ToolResult;
import com.study.prompt.PromptBuilder;
import com.study.tool.ToolExecutionResult;
import com.study.tool.ToolRegistry;
import com.study.tool.Truncate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

public final class Agent {
    public static final int MAX_ITERATIONS = 25;
    public static final int MAX_UNKNOWN_RUN = 3;
    public static final String NOTICE_MAX_ITER = "(已达最大迭代轮数 25，自动停止；可继续发消息推进。)";
    public static final String NOTICE_UNKNOWN_TOOLS = "(连续多轮只请求到未注册的工具，自动停止。)";
    public static final String NOTICE_STREAM_ERR = "(请求出错，本轮已中断。)";
    public static final String NOTICE_CANCELLED = "(已取消。)";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmClient client;
    private final ToolRegistry registry;

    public Agent(LlmClient client, ToolRegistry registry) {
        this.client = client;
        this.registry = registry;
    }

    public BlockingQueue<AgentEvent> run(ConversationManager conversation) {
        return run(conversation, Mode.NORMAL, new CancelToken());
    }

    public BlockingQueue<AgentEvent> run(ConversationManager conversation, Mode mode, CancelToken cancel) {
        BlockingQueue<AgentEvent> out = new LinkedBlockingQueue<>();
        Thread.ofVirtual().name("agent-loop").start(() -> runLoop(conversation, mode, cancel, out));
        return out;
    }

    private void runLoop(ConversationManager conversation, Mode mode, CancelToken cancel, BlockingQueue<AgentEvent> out) {
        int unknownRun = 0;
        try {
            List<Map<String, Object>> definitions = mode == Mode.PLAN
                    ? registry.readOnlyDefinitions()
                    : registry.getAllSchemas("openai");
            String suffix = mode == Mode.PLAN ? PromptBuilder.PLAN_MODE_REMINDER : "";

            for (int iter = 1; iter <= MAX_ITERATIONS; iter++) {
                if (!emit(cancel, out, new AgentEvent.Iter(iter))) {
                    finishCancelled(conversation, out);
                    return;
                }

                StreamCapture capture = streamOnce(conversation, definitions, suffix, out, cancel);
                if (capture.error != null) {
                    if (cancel.isCancelled()) {
                        finishCancelled(conversation, out);
                    } else {
                        out.add(new AgentEvent.Failed(capture.error));
                        ensureAssistantTail(conversation, NOTICE_STREAM_ERR);
                        out.add(new AgentEvent.Done());
                    }
                    return;
                }
                if (capture.usage != null) {
                    out.add(new AgentEvent.UsageReport(capture.usage.inputTokens(), capture.usage.outputTokens()));
                }

                if (capture.toolCalls.isEmpty()) {
                    String finalText = ensureFinal(capture.text.toString(), out);
                    conversation.addAssistantMessage(finalText);
                    out.add(new AgentEvent.Done());
                    return;
                }

                conversation.addAssistantWithToolCalls(capture.text.toString(), capture.toolCalls);
                unknownRun = allUnknown(capture.toolCalls) ? unknownRun + 1 : 0;
                BatchOutcome batch = executeBatched(capture.toolCalls, cancel, out);
                conversation.addToolResults(batch.results());

                if (!batch.completed()) {
                    finishCancelled(conversation, out);
                    return;
                }
                if (unknownRun >= MAX_UNKNOWN_RUN) {
                    out.add(new AgentEvent.Notice(NOTICE_UNKNOWN_TOOLS));
                    ensureAssistantTail(conversation, NOTICE_UNKNOWN_TOOLS);
                    out.add(new AgentEvent.Done());
                    return;
                }
            }

            out.add(new AgentEvent.Notice(NOTICE_MAX_ITER));
            ensureAssistantTail(conversation, NOTICE_MAX_ITER);
            out.add(new AgentEvent.Done());
        } catch (Exception e) {
            out.add(new AgentEvent.Failed(e.getMessage()));
            ensureAssistantTail(conversation, NOTICE_STREAM_ERR);
            out.add(new AgentEvent.Done());
        }
    }

    private StreamCapture streamOnce(ConversationManager conversation, List<Map<String, Object>> tools, String suffix,
                                     BlockingQueue<AgentEvent> out, CancelToken cancel) throws InterruptedException {
        StreamCapture capture = new StreamCapture();
        BlockingQueue<StreamEvent> stream = client.stream(conversation, tools, suffix);
        while (true) {
            if (cancel.isCancelled()) {
                capture.error = NOTICE_CANCELLED;
                return capture;
            }
            StreamEvent event = stream.take();
            if (event instanceof StreamEvent.TextDelta delta) {
                capture.text.append(delta.text());
                emit(cancel, out, new AgentEvent.Text(delta.text()));
            } else if (event instanceof StreamEvent.ThinkingDelta) {
                // Thinking is intentionally ignored and never enters conversation history.
            } else if (event instanceof StreamEvent.ToolCallComplete toolCall) {
                capture.toolCalls.add(toolCall.toToolCall());
            } else if (event instanceof StreamEvent.UsageEvent usage) {
                capture.usage = usage.usage();
            } else if (event instanceof StreamEvent.StreamEnd end) {
                if (capture.usage == null && (end.inputTokens() > 0 || end.outputTokens() > 0)) {
                    capture.usage = new com.study.llm.Usage(end.inputTokens(), end.outputTokens());
                }
                return capture;
            } else if (event instanceof StreamEvent.Error error) {
                capture.error = error.message();
                return capture;
            }
        }
    }

    private BatchOutcome executeBatched(List<ToolCall> calls, CancelToken cancel, BlockingQueue<AgentEvent> out)
            throws InterruptedException {
        ToolResult[] results = new ToolResult[calls.size()];
        int i = 0;
        while (i < calls.size()) {
            if (cancel.isCancelled()) {
                fillCancelled(calls, results, i);
                return new BatchOutcome(Arrays.stream(results).filter(Objects::nonNull).toList(), false);
            }
            if (registry.isReadOnly(calls.get(i).name())) {
                int start = i;
                int end = i + 1;
                while (end < calls.size() && registry.isReadOnly(calls.get(end).name())) {
                    end++;
                }
                executeReadOnlyBatch(calls, results, start, end, cancel, out);
                i = end;
            } else {
                results[i] = executeOne(calls.get(i), cancel, out);
                i++;
            }
        }
        return new BatchOutcome(List.of(results), true);
    }

    private void executeReadOnlyBatch(List<ToolCall> calls, ToolResult[] results, int start, int end,
                                      CancelToken cancel, BlockingQueue<AgentEvent> out) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(end - start);
        for (int i = start; i < end; i++) {
            ToolCall call = calls.get(i);
            out.add(new AgentEvent.Tool(new ToolEvent(call.id(), call.name(), preview(call.arguments()), Phase.START, "", false)));
        }
        for (int i = start; i < end; i++) {
            int index = i;
            Thread.ofVirtual().name("tool-" + calls.get(index).name()).start(() -> {
                try {
                    results[index] = executeOneNoEvents(calls.get(index), cancel);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        for (int i = start; i < end; i++) {
            ToolCall call = calls.get(i);
            ToolResult result = results[i];
            out.add(new AgentEvent.Tool(new ToolEvent(call.id(), call.name(), preview(call.arguments()), Phase.END,
                    summarize(result.content()), result.error())));
        }
    }

    private ToolResult executeOne(ToolCall call, CancelToken cancel, BlockingQueue<AgentEvent> out) {
        out.add(new AgentEvent.Tool(new ToolEvent(call.id(), call.name(), preview(call.arguments()), Phase.START, "", false)));
        ToolResult result = executeOneNoEvents(call, cancel);
        out.add(new AgentEvent.Tool(new ToolEvent(call.id(), call.name(), preview(call.arguments()), Phase.END,
                summarize(result.content()), result.error())));
        return result;
    }

    private ToolResult executeOneNoEvents(ToolCall call, CancelToken cancel) {
        if (cancel.isCancelled()) {
            return new ToolResult(call.id(), NOTICE_CANCELLED, true);
        }
        ToolExecutionResult result = registry.execute(call.name(), call.arguments());
        return new ToolResult(call.id(), result.content(), result.error());
    }

    private void fillCancelled(List<ToolCall> calls, ToolResult[] results, int from) {
        for (int i = from; i < calls.size(); i++) {
            results[i] = new ToolResult(calls.get(i).id(), NOTICE_CANCELLED, true);
        }
    }

    private boolean allUnknown(List<ToolCall> calls) {
        return calls.stream().allMatch(call -> registry.get(call.name()).isEmpty());
    }

    private void finishCancelled(ConversationManager conversation, BlockingQueue<AgentEvent> out) {
        ensureAssistantTail(conversation, NOTICE_CANCELLED);
        out.add(new AgentEvent.Notice(NOTICE_CANCELLED));
        out.add(new AgentEvent.Done());
    }

    private void ensureAssistantTail(ConversationManager conversation, String fallback) {
        if (!conversation.lastRole().orElse("").equals("assistant")) {
            conversation.addAssistantMessage(fallback);
        }
    }

    private String ensureFinal(String text, BlockingQueue<AgentEvent> out) {
        if (!text.isBlank()) {
            return text;
        }
        out.add(new AgentEvent.Text("(模型没有返回文本。)"));
        return "(模型没有返回文本。)";
    }

    private boolean emit(CancelToken cancel, BlockingQueue<AgentEvent> out, AgentEvent event) {
        if (cancel.isCancelled()) {
            return false;
        }
        out.add(event);
        return true;
    }

    private String preview(String arguments) {
        try {
            JsonNode root = JSON.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
            for (String key : List.of("path", "command", "pattern")) {
                if (root.path(key).isTextual()) {
                    return Truncate.chars(root.path(key).asText(), 80);
                }
            }
        } catch (Exception ignored) {
            // Fall back to raw arguments.
        }
        return Truncate.chars(arguments, 80);
    }

    private String summarize(String result) {
        String[] lines = result == null ? new String[0] : result.split("\\R");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 8); i++) {
            if (i > 0) {
                out.append(System.lineSeparator());
            }
            out.append(lines[i]);
        }
        if (lines.length > 8) {
            out.append(System.lineSeparator()).append("[truncated]");
        }
        return out.toString();
    }

    private static final class StreamCapture {
        private final StringBuilder text = new StringBuilder();
        private final List<ToolCall> toolCalls = new ArrayList<>();
        private com.study.llm.Usage usage;
        private String error;
    }

    private record BatchOutcome(List<ToolResult> results, boolean completed) {
    }
}
