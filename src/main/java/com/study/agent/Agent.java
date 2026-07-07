package com.study.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.conversation.ConversationManager;
import com.study.llm.LlmSystem;
import com.study.llm.LlmClient;
import com.study.llm.Request;
import com.study.llm.StreamEvent;
import com.study.llm.ToolCall;
import com.study.llm.ToolResult;
import com.study.permission.Decision;
import com.study.permission.Outcome;
import com.study.permission.PermissionEngine;
import com.study.permission.PermissionResult;
import com.study.prompt.Environment;
import com.study.prompt.PromptBuilder;
import com.study.prompt.Reminder;
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
import java.util.concurrent.TimeUnit;

public final class Agent {
    public static final int MAX_ITERATIONS = 25;
    public static final int MAX_UNKNOWN_RUN = 3;
    private static final int PLAN_REMINDER_INTERVAL = 4;
    public static final String NOTICE_MAX_ITER = "(已达最大迭代轮数 25，自动停止；可继续发消息推进。)";
    public static final String NOTICE_UNKNOWN_TOOLS = "(连续多轮只请求到未注册的工具，自动停止。)";
    public static final String NOTICE_STREAM_ERR = "(请求出错，本轮已中断。)";
    public static final String NOTICE_CANCELLED = "(已取消。)";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmClient client;
    private final ToolRegistry registry;
    private final String version;
    private final PermissionEngine permissions;

    public Agent(LlmClient client, ToolRegistry registry) {
        this(client, registry, "dev");
    }

    public Agent(LlmClient client, ToolRegistry registry, String version) {
        this(client, registry, version, PermissionEngine.create(java.nio.file.Path.of("")));
    }

    public Agent(LlmClient client, ToolRegistry registry, String version, PermissionEngine permissions) {
        this.client = client;
        this.registry = registry;
        this.version = version == null || version.isBlank() ? "dev" : version;
        this.permissions = permissions == null ? PermissionEngine.create(java.nio.file.Path.of("")) : permissions;
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
            String stableSystem = PromptBuilder.buildSystemPrompt();
            String environment = Environment.gather(version, client.model()).render();

            for (int iter = 1; iter <= MAX_ITERATIONS; iter++) {
                if (!emit(cancel, out, new AgentEvent.Iter(iter))) {
                    finishCancelled(conversation, out);
                    return;
                }

                String reminder = reminderFor(mode, iter);
                StreamCapture capture = streamOnce(conversation, definitions, stableSystem, environment, reminder, out, cancel);
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
                    out.add(new AgentEvent.UsageReport(
                            capture.usage.inputTokens(),
                            capture.usage.outputTokens(),
                            capture.usage.cacheWrite(),
                            capture.usage.cacheRead()));
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

    private StreamCapture streamOnce(ConversationManager conversation, List<Map<String, Object>> tools,
                                     String stableSystem, String environment, String reminder,
                                     BlockingQueue<AgentEvent> out, CancelToken cancel) throws InterruptedException {
        StreamCapture capture = new StreamCapture();
        Request request = new Request(conversation.getMessages(), tools, new LlmSystem(stableSystem, environment), reminder);
        BlockingQueue<StreamEvent> stream = client.stream(request);
        while (true) {
            if (cancel.isCancelled()) {
                capture.error = NOTICE_CANCELLED;
                return capture;
            }
            StreamEvent event = stream.take();
            switch (event) {
                case StreamEvent.TextDelta delta -> {
                    capture.text.append(delta.text());
                    emit(cancel, out, new AgentEvent.Text(delta.text()));
                }
                case StreamEvent.ThinkingDelta thinkingDelta -> {
                    // Thinking is intentionally ignored and never enters conversation history.
                }
                case StreamEvent.ToolCallComplete toolCall -> capture.toolCalls.add(toolCall.toToolCall());
                case StreamEvent.UsageEvent usage -> capture.usage = usage.usage();
                case StreamEvent.StreamEnd end -> {
                    if (capture.usage == null && (end.inputTokens() > 0 || end.outputTokens() > 0)) {
                        capture.usage = new com.study.llm.Usage(end.inputTokens(), end.outputTokens());
                    }
                    return capture;
                }
                case StreamEvent.Error error -> {
                    capture.error = error.message();
                    return capture;
                }
                default -> {
                }
            }
        }
    }

    private String reminderFor(Mode mode, int iter) {
        if (mode != Mode.PLAN) {
            return "";
        }
        boolean full = iter == 1 || (iter - 1) % PLAN_REMINDER_INTERVAL == 0;
        return Reminder.plan(full);
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
                    results[index] = executeOneNoEvents(calls.get(index), cancel, out);
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
        ToolResult result = executeOneNoEvents(call, cancel, out);
        out.add(new AgentEvent.Tool(new ToolEvent(call.id(), call.name(), preview(call.arguments()), Phase.END,
                summarize(result.content()), result.error())));
        return result;
    }

    private ToolResult executeOneNoEvents(ToolCall call, CancelToken cancel, BlockingQueue<AgentEvent> out) {
        if (cancel.isCancelled()) {
            return new ToolResult(call.id(), NOTICE_CANCELLED, true);
        }
        if (registry.get(call.name()).isEmpty()) {
            ToolExecutionResult result = registry.execute(call.name(), call.arguments());
            return new ToolResult(call.id(), result.content(), result.error());
        }
        PermissionResult permission = permissions.check(call);
        if (permission.decision() == Decision.DENY) {
            return new ToolResult(call.id(), "Permission denied: " + permission.reason(), true);
        }
        if (permission.decision() == Decision.ASK) {
            Outcome outcome = requestApproval(call, permission.reason(), cancel, out);
            if (outcome != Outcome.ALLOW_ONCE) {
                return new ToolResult(call.id(), "Permission denied by user: " + permission.reason(), true);
            }
        }
        ToolExecutionResult result = registry.execute(call.name(), call.arguments());
        return new ToolResult(call.id(), result.content(), result.error());
    }

    private Outcome requestApproval(ToolCall call, String reason, CancelToken cancel, BlockingQueue<AgentEvent> out) {
        ApprovalRequest request = new ApprovalRequest(call.name(), call.arguments(), reason);
        out.add(new AgentEvent.Approval(request));
        while (!cancel.isCancelled()) {
            try {
                Outcome outcome = request.respond().poll(100, TimeUnit.MILLISECONDS);
                if (outcome != null) {
                    return outcome;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Outcome.DENY_ONCE;
            }
        }
        return Outcome.DENY_ONCE;
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
