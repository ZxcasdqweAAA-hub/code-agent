package com.study.agent;

import com.study.conversation.ConversationManager;
import com.study.llm.LlmClient;
import com.study.llm.Request;
import com.study.llm.StreamEvent;
import com.study.llm.Usage;
import com.study.permission.Outcome;
import com.study.permission.PermissionEngine;
import com.study.prompt.Reminder;
import com.study.tool.Tool;
import com.study.tool.ToolExecutionResult;
import com.study.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTest {
    @TempDir
    Path tempDir;

    @Test
    void runsMultipleToolRoundsUntilFinalAnswer() throws Exception {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("read file");
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("call-1", "ReadFile", "{\"path\":\"pom.xml\"}"), new StreamEvent.StreamEnd("tool_calls", 0, 0)),
                List.of(new StreamEvent.TextDelta("done"), new StreamEvent.StreamEnd("stop", 0, 0))
        ));

        BlockingQueue<AgentEvent> events = new Agent(client, ToolRegistry.createDefault()).run(conversation, Mode.NORMAL, new CancelToken());
        List<AgentEvent> collected = drainUntilDone(events);

        assertTrue(collected.stream().anyMatch(event -> event instanceof AgentEvent.Iter iter && iter.value() == 1));
        assertTrue(collected.stream().anyMatch(event -> event instanceof AgentEvent.Iter iter && iter.value() == 2));
        assertTrue(collected.stream().anyMatch(event -> event instanceof AgentEvent.Tool tool && tool.event().phase() == Phase.START));
        assertTrue(collected.stream().anyMatch(event -> event instanceof AgentEvent.Tool tool && tool.event().phase() == Phase.END));
        assertTrue(collected.stream().anyMatch(event -> event instanceof AgentEvent.Text text && text.delta().contains("done")));
        assertEquals("assistant", conversation.getMessages().get(conversation.getMessages().size() - 1).role());
        assertEquals("done", conversation.getMessages().get(conversation.getMessages().size() - 1).content());
    }

    @Test
    void stopsAfterUnknownToolRunLimit() throws Exception {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("unknown");
        FakeClient client = new FakeClient(List.of(), true);

        BlockingQueue<AgentEvent> events = new Agent(client, ToolRegistry.createDefault()).run(conversation, Mode.NORMAL, new CancelToken());
        List<AgentEvent> collected = drainUntilDone(events);

        assertEquals(Agent.MAX_UNKNOWN_RUN, client.calls);
        assertTrue(collected.stream().anyMatch(event -> event instanceof AgentEvent.Notice notice
                && notice.message().equals(Agent.NOTICE_UNKNOWN_TOOLS)));
        assertEquals("assistant", conversation.lastRole().orElseThrow());
    }

    @Test
    void planModeSendsReadOnlyToolsAndReminder() throws Exception {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("plan");
        FakeClient client = new FakeClient(List.of(List.of(new StreamEvent.TextDelta("plan"), new StreamEvent.StreamEnd("stop", 0, 0))));

        BlockingQueue<AgentEvent> events = new Agent(client, ToolRegistry.createDefault()).run(conversation, Mode.PLAN, new CancelToken());
        drainUntilDone(events);

        assertTrue(client.lastRequest.system().stable().contains("Code Agent"));
        assertTrue(client.lastRequest.system().environment().contains("Working directory"));
        assertTrue(client.lastRequest.reminder().contains("<system-reminder>"));
        assertTrue(client.lastRequest.reminder().contains(Reminder.PLAN_REMINDER_FULL));
        assertTrue(client.lastTools.stream().allMatch(tool -> List.of("ReadFile", "Glob", "Grep").contains(tool.get("name"))));
    }

    @Test
    void forwardsCacheUsage() throws Exception {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("usage");
        FakeClient client = new FakeClient(List.of(List.of(
                new StreamEvent.UsageEvent(new Usage(10, 5, 3, 7)),
                new StreamEvent.TextDelta("ok"),
                new StreamEvent.StreamEnd("stop", 0, 0)
        )));

        BlockingQueue<AgentEvent> events = new Agent(client, ToolRegistry.createDefault()).run(conversation, Mode.NORMAL, new CancelToken());
        List<AgentEvent> collected = drainUntilDone(events);

        AgentEvent.UsageReport usage = collected.stream()
                .filter(AgentEvent.UsageReport.class::isInstance)
                .map(AgentEvent.UsageReport.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(10, usage.inputTokens());
        assertEquals(5, usage.outputTokens());
        assertEquals(3, usage.cacheWrite());
        assertEquals(7, usage.cacheRead());
    }

    @Test
    void readOnlyToolsRunConcurrentlyBeforeWriteTools() throws Exception {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("batch");
        FakeClient client = new FakeClient(List.of(
                List.of(
                        new StreamEvent.ToolCallComplete("ro-1", "Readonly", "{}"),
                        new StreamEvent.ToolCallComplete("ro-2", "Readonly", "{}"),
                        new StreamEvent.ToolCallComplete("rw", "Writey", "{}"),
                        new StreamEvent.StreamEnd("tool_calls", 0, 0)),
                List.of(new StreamEvent.TextDelta("ok"), new StreamEvent.StreamEnd("stop", 0, 0))
        ));
        ToolRegistry registry = new ToolRegistry();
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger completedReads = new AtomicInteger();
        AtomicInteger writesStartedAfterReads = new AtomicInteger();
        registry.register(new TestTool("Readonly", true, () -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            bothStarted.countDown();
            bothStarted.await(2, TimeUnit.SECONDS);
            running.decrementAndGet();
            completedReads.incrementAndGet();
        }));
        registry.register(new TestTool("Writey", false, () -> {
            if (completedReads.get() == 2) {
                writesStartedAfterReads.incrementAndGet();
            }
        }));

        BlockingQueue<AgentEvent> events = new Agent(client, registry, "dev", PermissionEngine.create(tempDir)).run(conversation, Mode.NORMAL, new CancelToken());
        drainUntilDone(events, Outcome.ALLOW_ONCE);

        assertTrue(peak.get() >= 2);
        assertEquals(1, writesStartedAfterReads.get());
    }

    @Test
    void asksBeforeWriteToolAndContinuesWhenAllowed() throws Exception {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("write");
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("write-1", "WriteFile",
                        "{\"path\":\"" + jsonPath(tempDir.resolve("out.txt")) + "\",\"content\":\"hello\"}"),
                        new StreamEvent.StreamEnd("tool_calls", 0, 0)),
                List.of(new StreamEvent.TextDelta("wrote"), new StreamEvent.StreamEnd("stop", 0, 0))
        ));

        BlockingQueue<AgentEvent> events = new Agent(client, ToolRegistry.createDefault(), "dev", PermissionEngine.create(tempDir))
                .run(conversation, Mode.NORMAL, new CancelToken());
        List<AgentEvent> collected = drainUntilDone(events, Outcome.ALLOW_ONCE);

        assertTrue(collected.stream().anyMatch(event -> event instanceof AgentEvent.Approval));
        assertTrue(collected.stream().anyMatch(event -> event instanceof AgentEvent.Text text && text.delta().contains("wrote")));
    }

    @Test
    void denialIsReturnedAsToolResult() throws Exception {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("write");
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("write-1", "WriteFile",
                        "{\"path\":\"" + jsonPath(tempDir.resolve("out.txt")) + "\",\"content\":\"hello\"}"),
                        new StreamEvent.StreamEnd("tool_calls", 0, 0)),
                List.of(new StreamEvent.TextDelta("denied handled"), new StreamEvent.StreamEnd("stop", 0, 0))
        ));

        BlockingQueue<AgentEvent> events = new Agent(client, ToolRegistry.createDefault(), "dev", PermissionEngine.create(tempDir))
                .run(conversation, Mode.NORMAL, new CancelToken());
        drainUntilDone(events, Outcome.DENY_ONCE);

        assertTrue(conversation.getMessages().stream()
                .flatMap(message -> message.toolResults().stream())
                .anyMatch(result -> result.error() && result.content().contains("Permission denied by user")));
    }

    @Test
    void blacklistDenialDoesNotAskUser() throws Exception {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("danger");
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("bash-1", "Bash", "{\"command\":\"git reset --hard HEAD\"}"),
                        new StreamEvent.StreamEnd("tool_calls", 0, 0)),
                List.of(new StreamEvent.TextDelta("safe"), new StreamEvent.StreamEnd("stop", 0, 0))
        ));

        BlockingQueue<AgentEvent> events = new Agent(client, ToolRegistry.createDefault(), "dev", PermissionEngine.create(tempDir))
                .run(conversation, Mode.NORMAL, new CancelToken());
        List<AgentEvent> collected = drainUntilDone(events);

        assertTrue(collected.stream().noneMatch(event -> event instanceof AgentEvent.Approval));
        assertTrue(conversation.getMessages().stream()
                .flatMap(message -> message.toolResults().stream())
                .anyMatch(result -> result.error() && result.content().contains("Permission denied")));
    }

    private List<AgentEvent> drainUntilDone(BlockingQueue<AgentEvent> events) throws InterruptedException {
        return drainUntilDone(events, null);
    }

    private List<AgentEvent> drainUntilDone(BlockingQueue<AgentEvent> events, Outcome approval) throws InterruptedException {
        List<AgentEvent> collected = new ArrayList<>();
        while (true) {
            AgentEvent event = events.poll(5, TimeUnit.SECONDS);
            if (event == null) {
                throw new AssertionError("Timed out waiting for agent event");
            }
            collected.add(event);
            if (event instanceof AgentEvent.Approval request && approval != null) {
                request.request().respond().offer(approval);
            }
            if (event instanceof AgentEvent.Done || event instanceof AgentEvent.Failed) {
                return collected;
            }
        }
    }

    private String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static final class FakeClient implements LlmClient {
        private final List<List<StreamEvent>> scripts;
        private final boolean endlessUnknown;
        private int index;
        private int calls;
        private List<Map<String, Object>> lastTools = List.of();
        private Request lastRequest;

        private FakeClient(List<List<StreamEvent>> scripts) {
            this(scripts, false);
        }

        private FakeClient(List<List<StreamEvent>> scripts, boolean endlessUnknown) {
            this.scripts = scripts;
            this.endlessUnknown = endlessUnknown;
        }

        @Override
        public BlockingQueue<StreamEvent> stream(Request request) {
            BlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
            calls++;
            lastRequest = request;
            lastTools = List.copyOf(request.tools());
            if (endlessUnknown) {
                queue.add(new StreamEvent.ToolCallComplete("unknown-" + calls, "MissingTool", "{}"));
                queue.add(new StreamEvent.StreamEnd("tool_calls", 0, 0));
            } else {
                scripts.get(index++).forEach(queue::add);
            }
            return queue;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public String model() {
            return "fake-model";
        }
    }

    private record TestTool(String name, boolean readOnly, ThrowingRunnable runnable) implements Tool {
        @Override
        public String description() {
            return name;
        }

        @Override
        public Map<String, Object> schema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public ToolExecutionResult execute(Map<String, Object> args) {
            try {
                runnable.run();
                return ToolExecutionResult.ok(name + " done");
            } catch (Exception e) {
                return ToolExecutionResult.error(e.getMessage());
            }
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
