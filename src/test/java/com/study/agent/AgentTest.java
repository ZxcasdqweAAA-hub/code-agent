package com.study.agent;

import com.study.conversation.ConversationManager;
import com.study.compact.CompactRuntime;
import com.study.hook.HookEngine;
import com.study.hook.HookRuntime;
import com.study.llm.LlmClient;
import com.study.llm.Request;
import com.study.llm.StreamEvent;
import com.study.llm.Usage;
import com.study.permission.Outcome;
import com.study.permission.Mode;
import com.study.permission.PermissionEngine;
import com.study.prompt.Reminder;
import com.study.skills.ActiveSkills;
import com.study.skills.Catalog;
import com.study.tool.LoadSkillTool;
import com.study.tool.Tool;
import com.study.tool.ToolExecutionResult;
import com.study.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTest {
    @TempDir
    Path tempDir;

    @Test
    void cancellingBlockedModelStreamRollsBackAndFinishesOnce() throws Exception {
        ConversationManager conversation = new ConversationManager();
        conversation.addAssistantMessage("stable history");
        TurnCheckpoint checkpoint = TurnCheckpoint.capture(conversation);
        conversation.addUserMessage("current request");
        com.study.llm.TestLlmStream blocked = new com.study.llm.TestLlmStream(List.of());
        LlmClient client = new LlmClient() {
            @Override
            public com.study.llm.LlmStream stream(Request request) {
                return blocked;
            }

            @Override
            public String name() {
                return "blocked";
            }

            @Override
            public String model() {
                return "blocked-model";
            }
        };
        CancelToken cancel = new CancelToken();
        BlockingQueue<AgentEvent> queue = new Agent(client, new ToolRegistry(), "dev",
                PermissionEngine.create(tempDir), null)
                .run(conversation, Mode.DEFAULT, cancel, checkpoint);
        AgentEvent first = queue.poll(2, TimeUnit.SECONDS);
        assertTrue(first instanceof AgentEvent.Iter);

        cancel.cancel();
        List<AgentEvent> events = new ArrayList<>();
        events.add(first);
        while (events.stream().noneMatch(AgentEvent.Finished.class::isInstance)) {
            AgentEvent event = queue.poll(2, TimeUnit.SECONDS);
            if (event == null) {
                throw new AssertionError("Timed out waiting for cancellation");
            }
            events.add(event);
        }

        assertTrue(blocked.isCancelled());
        assertTrue(blocked.isClosed());
        assertEquals(checkpoint.messagesBeforeTurn(), conversation.getMessages());
        assertTrue(events.stream().anyMatch(AgentEvent.TurnRolledBack.class::isInstance));
        assertEquals(TurnStatus.CANCELLED, terminal(events).status());
        assertEquals(0, queue.size());
    }

    @Test
    void runsMultipleToolRoundsUntilFinalAnswer() throws Exception {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("read file");
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("call-1", "ReadFile", "{\"path\":\"pom.xml\"}"), new StreamEvent.StreamEnd("tool_calls", 0, 0)),
                List.of(new StreamEvent.TextDelta("done"), new StreamEvent.StreamEnd("stop", 0, 0))
        ));

        BlockingQueue<AgentEvent> events = new Agent(client, ToolRegistry.createDefault()).run(conversation, Mode.DEFAULT, new CancelToken());
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

        BlockingQueue<AgentEvent> events = new Agent(client, ToolRegistry.createDefault()).run(conversation, Mode.DEFAULT, new CancelToken());
        List<AgentEvent> collected = drainUntilDone(events, Outcome.DENY_ONCE);

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
        assertTrue(client.lastTools.stream().allMatch(tool -> List.of("ReadFile", "Glob", "Grep", "ToolSearch").contains(tool.get("name"))));
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

        BlockingQueue<AgentEvent> events = new Agent(client, ToolRegistry.createDefault()).run(conversation, Mode.DEFAULT, new CancelToken());
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

        BlockingQueue<AgentEvent> events = new Agent(client, registry, "dev", PermissionEngine.create(tempDir)).run(conversation, Mode.DEFAULT, new CancelToken());
        drainUntilDone(events, Outcome.ALLOW_ONCE);

        assertTrue(peak.get() >= 2);
        assertEquals(1, writesStartedAfterReads.get());
    }

    @Test
    void successfulToolSearchRefreshesDefinitionsForNextRound() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.study.tool.ToolSearchTool(registry));
        registry.register(new DeferredTestTool("DeferredExample", true));
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("load deferred tool");
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("search-1", "ToolSearch",
                                "{\"query\":\"select:DeferredExample\"}"),
                        new StreamEvent.StreamEnd("tool_calls", 0, 0)),
                List.of(new StreamEvent.TextDelta("loaded"), new StreamEvent.StreamEnd("stop", 0, 0))
        ));

        BlockingQueue<AgentEvent> events = new Agent(client, registry, "dev", PermissionEngine.create(tempDir))
                .run(conversation, Mode.DEFAULT, new CancelToken());
        drainUntilDone(events, Outcome.ALLOW_ONCE);

        assertFalse(toolNames(client.toolsByRequest.get(0)).contains("DeferredExample"));
        assertTrue(toolNames(client.toolsByRequest.get(1)).contains("DeferredExample"));
    }

    @Test
    void failedToolSearchDoesNotRefreshDefinitions() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new RegisteringResultTool("ToolSearch", true, registry,
                new TestTool("AddedAfterFailure", true, () -> { }),
                ToolExecutionResult.error("search failed")));
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("failed search");
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("search-failed", "ToolSearch", "{}"),
                        new StreamEvent.StreamEnd("tool_calls", 0, 0)),
                List.of(new StreamEvent.TextDelta("handled"), new StreamEvent.StreamEnd("stop", 0, 0))
        ));

        BlockingQueue<AgentEvent> events = new Agent(client, registry, "dev", PermissionEngine.create(tempDir))
                .run(conversation, Mode.DEFAULT, new CancelToken());
        drainUntilDone(events, Outcome.ALLOW_ONCE);

        assertTrue(registry.names().contains("AddedAfterFailure"));
        assertFalse(toolNames(client.toolsByRequest.get(1)).contains("AddedAfterFailure"));
    }

    @Test
    void successfulToolSearchInMixedBatchRefreshesDefinitions() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TestTool("Readonly", true, () -> { }));
        registry.register(new com.study.tool.ToolSearchTool(registry));
        registry.register(new DeferredTestTool("MixedDeferred", true));
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("mixed batch");
        FakeClient client = new FakeClient(List.of(
                List.of(
                        new StreamEvent.ToolCallComplete("read-1", "Readonly", "{}"),
                        new StreamEvent.ToolCallComplete("search-1", "ToolSearch",
                                "{\"query\":\"select:MixedDeferred\"}"),
                        new StreamEvent.StreamEnd("tool_calls", 0, 0)),
                List.of(new StreamEvent.TextDelta("loaded"), new StreamEvent.StreamEnd("stop", 0, 0))
        ));

        BlockingQueue<AgentEvent> events = new Agent(client, registry, "dev", PermissionEngine.create(tempDir))
                .run(conversation, Mode.DEFAULT, new CancelToken());
        drainUntilDone(events, Outcome.ALLOW_ONCE);

        assertTrue(toolNames(client.toolsByRequest.get(1)).contains("MixedDeferred"));
    }

    @Test
    void refreshedDefinitionsKeepPlanModeReadOnlyFilter() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.study.tool.ToolSearchTool(registry));
        registry.register(new DeferredTestTool("PlanRead", true));
        registry.register(new DeferredTestTool("PlanWrite", false));
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("plan search");
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("search-plan", "ToolSearch",
                                "{\"query\":\"select:PlanRead,PlanWrite\"}"),
                        new StreamEvent.StreamEnd("tool_calls", 0, 0)),
                List.of(new StreamEvent.TextDelta("planned"), new StreamEvent.StreamEnd("stop", 0, 0))
        ));

        BlockingQueue<AgentEvent> events = new Agent(client, registry, "dev", PermissionEngine.create(tempDir))
                .run(conversation, Mode.PLAN, new CancelToken());
        drainUntilDone(events, Outcome.ALLOW_ONCE);

        assertTrue(toolNames(client.toolsByRequest.get(1)).contains("PlanRead"));
        assertFalse(toolNames(client.toolsByRequest.get(1)).contains("PlanWrite"));
    }

    @Test
    void refreshedDefinitionsKeepSubagentAllowedToolFilter() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.study.tool.ToolSearchTool(registry));
        registry.register(new DeferredTestTool("AllowedDeferred", true));
        registry.register(new DeferredTestTool("BlockedDeferred", true));
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("subagent search");
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("search-subagent", "ToolSearch",
                                "{\"query\":\"select:AllowedDeferred,BlockedDeferred\"}"),
                        new StreamEvent.StreamEnd("tool_calls", 0, 0)),
                List.of(new StreamEvent.TextDelta("filtered"), new StreamEvent.StreamEnd("stop", 0, 0))
        ));
        Agent agent = new Agent(client, registry, "dev", PermissionEngine.create(tempDir), null, "", null,
                null, List.of("ToolSearch", "AllowedDeferred"), HookEngine.empty(), new HookRuntime(),
                0, true, true);

        BlockingQueue<AgentEvent> events = agent.run(conversation, Mode.DEFAULT, new CancelToken());
        drainUntilDone(events);

        assertTrue(toolNames(client.toolsByRequest.get(1)).contains("AllowedDeferred"));
        assertFalse(toolNames(client.toolsByRequest.get(1)).contains("BlockedDeferred"));
    }

    @Test
    void toolSearchWithoutMatchesKeepsSameToolSet() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.study.tool.ToolSearchTool(registry));
        registry.register(new DeferredTestTool("StillDeferred", true));
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("search missing tool");
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("search-missing", "ToolSearch",
                                "{\"query\":\"select:Missing\"}"),
                        new StreamEvent.StreamEnd("tool_calls", 0, 0)),
                List.of(new StreamEvent.TextDelta("none"), new StreamEvent.StreamEnd("stop", 0, 0))
        ));

        BlockingQueue<AgentEvent> events = new Agent(client, registry, "dev", PermissionEngine.create(tempDir))
                .run(conversation, Mode.DEFAULT, new CancelToken());
        drainUntilDone(events, Outcome.ALLOW_ONCE);

        assertEquals(toolNames(client.toolsByRequest.get(0)), toolNames(client.toolsByRequest.get(1)));
        assertFalse(toolNames(client.toolsByRequest.get(1)).contains("StillDeferred"));
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
                .run(conversation, Mode.DEFAULT, new CancelToken());
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
                .run(conversation, Mode.DEFAULT, new CancelToken());
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
                .run(conversation, Mode.DEFAULT, new CancelToken());
        List<AgentEvent> collected = drainUntilDone(events);

        assertTrue(collected.stream().noneMatch(event -> event instanceof AgentEvent.Approval));
        assertTrue(conversation.getMessages().stream()
                .flatMap(message -> message.toolResults().stream())
                .anyMatch(result -> result.error() && result.content().contains("Permission denied")));
    }

    @Test
    void loadSkillAutoExecutesInlineModeInMainConversation() throws Exception {
        writeSkill("demo", "inline", "none", "Inline Body $ARGUMENTS");
        Catalog catalog = Catalog.load(tempDir, Set.of());
        ActiveSkills active = new ActiveSkills();
        ToolRegistry registry = ToolRegistry.createDefault();
        registry.register(new LoadSkillTool(catalog, active, registry));
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("use demo");
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("load-1", "load_skill", "{\"name\":\"demo\"}"),
                        new StreamEvent.StreamEnd("tool_calls", 0, 0)),
                List.of(new StreamEvent.TextDelta("inline done"), new StreamEvent.StreamEnd("stop", 0, 0))
        ));

        BlockingQueue<AgentEvent> events = new Agent(client, registry, "dev", PermissionEngine.create(tempDir),
                null, "", null, active, List.of()).run(conversation, Mode.DEFAULT, new CancelToken());
        drainUntilDone(events);

        assertEquals(2, client.calls);
        assertTrue(conversation.getMessages().stream()
                .anyMatch(message -> "user".equals(message.role()) && message.content().contains("Inline Body")));
        assertEquals("inline done", conversation.getMessages().getLast().content());
        assertTrue(active.names().isEmpty());
    }

    @Test
    void loadSkillAutoExecutesForkModeAndOnlyReturnsFinalResult() throws Exception {
        writeSkill("demo", "fork", "none", "Fork Body");
        Catalog catalog = Catalog.load(tempDir, Set.of());
        ActiveSkills active = new ActiveSkills();
        ToolRegistry registry = ToolRegistry.createDefault();
        registry.register(new LoadSkillTool(catalog, active, registry));
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("use demo");
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("load-1", "load_skill", "{\"name\":\"demo\"}"),
                        new StreamEvent.StreamEnd("tool_calls", 0, 0)),
                List.of(new StreamEvent.TextDelta("fork result"), new StreamEvent.StreamEnd("stop", 0, 0))
        ));

        BlockingQueue<AgentEvent> events = new Agent(client, registry, "dev", PermissionEngine.create(tempDir),
                null, "", null, active, List.of()).run(conversation, Mode.DEFAULT, new CancelToken());
        drainUntilDone(events);

        assertEquals(2, client.calls);
        assertTrue(conversation.getMessages().stream()
                .noneMatch(message -> "user".equals(message.role()) && message.content().contains("Fork Body")));
        assertEquals("fork result", conversation.getMessages().getLast().content());
        assertTrue(active.names().isEmpty());
    }

    @Test
    void skillToolsAreVisibleOnlyAfterSkillLoadAndStayOutOfGlobalRegistry() throws Exception {
        writeSkill("tool-skill", "inline", "none", "Use the specialized tool.");
        Files.writeString(tempDir.resolve(".code-agent/skills/tool-skill/tool.json"), """
                {
                  "tools": [
                    {
                      "name": "skill-check",
                      "description": "Run the skill check",
                      "input_schema": {"type":"object","properties":{}},
                      "command": ["cmd", "/C", "echo skill-check"]
                    }
                  ]
                }
                """);
        Catalog catalog = Catalog.load(tempDir, Set.of());
        ActiveSkills active = new ActiveSkills();
        ToolRegistry registry = ToolRegistry.createDefault();
        registry.register(new LoadSkillTool(catalog, active, registry));
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("use tool skill");
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("load-tool-skill", "load_skill", "{\"name\":\"tool-skill\"}"),
                        new StreamEvent.StreamEnd("tool_calls", 0, 0)),
                List.of(new StreamEvent.ToolCallComplete("skill-check-1", "skill-check", "{}"),
                        new StreamEvent.StreamEnd("tool_calls", 0, 0)),
                List.of(new StreamEvent.TextDelta("done"), new StreamEvent.StreamEnd("stop", 0, 0))
        ));

        BlockingQueue<AgentEvent> events = new Agent(client, registry, "dev", PermissionEngine.create(tempDir))
                .run(conversation, Mode.DEFAULT, new CancelToken());
        drainUntilDone(events, Outcome.ALLOW_ONCE);

        assertFalse(toolNames(client.toolsByRequest.get(0)).contains("skill-check"));
        assertTrue(toolNames(client.toolsByRequest.get(1)).contains("skill-check"));
        assertTrue(registry.get("skill-check").isEmpty());
    }

    @Test
    void compactFailureBeforeToolRollsBackCurrentTurn() throws Exception {
        ConversationManager conversation = new ConversationManager();
        conversation.addAssistantMessage("stable history");
        TurnCheckpoint checkpoint = TurnCheckpoint.capture(conversation);
        conversation.addUserMessage("u".repeat(4_000));
        CompactRuntime runtime = CompactRuntime.create(tempDir);
        runtime.setContextWindow(81_000);
        List<List<StreamEvent>> failures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            failures.add(List.of(new StreamEvent.Error("prompt_too_long")));
        }

        BlockingQueue<AgentEvent> queue = new Agent(new FakeClient(failures), new ToolRegistry(), "dev",
                PermissionEngine.create(tempDir), runtime)
                .run(conversation, Mode.DEFAULT, new CancelToken(), checkpoint);
        List<AgentEvent> events = drainUntilDone(queue);

        assertEquals(checkpoint.messagesBeforeTurn(), conversation.getMessages());
        assertTrue(events.stream().anyMatch(AgentEvent.TurnRolledBack.class::isInstance));
        assertEquals(TurnStatus.FAILED, terminal(events).status());
    }

    @Test
    void ordinaryFailureRollsBackWithTruncationInsteadOfSnapshot() throws Exception {
        List<List<com.study.conversation.Message>> snapshots = new ArrayList<>();
        List<com.study.conversation.ConversationTruncation> truncations = new ArrayList<>();
        ConversationManager conversation = new ConversationManager(
                ignored -> { }, snapshots::add, truncations::add);
        conversation.copyFrom(List.of(com.study.conversation.Message.assistant("stable")));
        TurnCheckpoint checkpoint = TurnCheckpoint.capture(conversation);
        conversation.addUserMessage("current request");
        FakeClient client = new FakeClient(List.of(List.of(new StreamEvent.Error("request failed"))));

        List<AgentEvent> events = drainUntilDone(new Agent(client, new ToolRegistry(), "dev",
                PermissionEngine.create(tempDir), CompactRuntime.create(tempDir))
                .run(conversation, Mode.DEFAULT, new CancelToken(), checkpoint));

        assertEquals(checkpoint.messagesBeforeTurn(), conversation.getMessages());
        assertEquals(1, truncations.size());
        assertEquals(2, truncations.getFirst().fromSize());
        assertEquals(1, truncations.getFirst().toSize());
        assertTrue(snapshots.isEmpty());
        assertTrue(events.stream().anyMatch(AgentEvent.TurnRolledBack.class::isInstance));
    }

    @Test
    void emergencyCompactFailureRollsBackAndStopsLoop() throws Exception {
        ConversationManager conversation = new ConversationManager();
        conversation.addAssistantMessage("stable history");
        TurnCheckpoint checkpoint = TurnCheckpoint.capture(conversation);
        conversation.addUserMessage("current request");
        CompactRuntime runtime = CompactRuntime.create(tempDir);
        runtime.setContextWindow(128_000);
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.Error("prompt_too_long")),
                List.of(new StreamEvent.Error("summary service unavailable"))));

        List<AgentEvent> events = drainUntilDone(new Agent(client, new ToolRegistry(), "dev",
                PermissionEngine.create(tempDir), runtime)
                .run(conversation, Mode.DEFAULT, new CancelToken(), checkpoint));

        assertEquals(2, client.calls);
        assertEquals(checkpoint.messagesBeforeTurn(), conversation.getMessages());
        assertTrue(events.stream().anyMatch(AgentEvent.TurnRolledBack.class::isInstance));
        assertTrue(terminal(events).reason().contains("紧急压缩上下文失败"));
    }

    @Test
    void emergencyRetryFailureUsesSameReminderAndRollsBack() throws Exception {
        ConversationManager conversation = new ConversationManager();
        TurnCheckpoint checkpoint = TurnCheckpoint.capture(conversation);
        conversation.addUserMessage("plan current request");
        CompactRuntime runtime = CompactRuntime.create(tempDir);
        runtime.setContextWindow(128_000);
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.Error("prompt_too_long")),
                List.of(new StreamEvent.TextDelta("<summary>summary</summary>"),
                        new StreamEvent.StreamEnd("stop", 10, 5)),
                List.of(new StreamEvent.Error("prompt_too_long after compact"))));

        List<AgentEvent> events = drainUntilDone(new Agent(client, ToolRegistry.createDefault(), "dev",
                PermissionEngine.create(tempDir), runtime)
                .run(conversation, Mode.PLAN, new CancelToken(), checkpoint));

        assertEquals(3, client.calls);
        assertEquals(client.requests.getFirst().reminder(), client.requests.getLast().reminder());
        assertTrue(client.requests.getLast().reminder().contains(Reminder.PLAN_REMINDER_FULL));
        assertEquals(checkpoint.messagesBeforeTurn(), conversation.getMessages());
        assertTrue(events.stream().anyMatch(AgentEvent.TurnRolledBack.class::isInstance));
        assertTrue(terminal(events).reason().contains("紧急压缩后重试失败"));
    }

    @Test
    void rollbackPersistenceFailureDoesNotEmitFalseRollbackEvent() throws Exception {
        ConversationManager conversation = new ConversationManager(null, ignored -> {
            throw new com.study.conversation.ConversationPersistenceException(
                    "snapshot failed", new java.io.IOException("disk full"));
        });
        conversation.addAssistantMessage("stable history");
        TurnCheckpoint checkpoint = TurnCheckpoint.capture(conversation);
        conversation.addUserMessage("u".repeat(4_000));
        List<com.study.conversation.Message> current = conversation.getMessages();
        CompactRuntime runtime = CompactRuntime.create(tempDir);
        runtime.setContextWindow(81_000);
        FakeClient client = new FakeClient(List.of(List.of(new StreamEvent.Error("summary unavailable"))));

        List<AgentEvent> events = drainUntilDone(new Agent(client, new ToolRegistry(), "dev",
                PermissionEngine.create(tempDir), runtime)
                .run(conversation, Mode.DEFAULT, new CancelToken(), checkpoint));

        assertEquals(current, conversation.getMessages());
        assertTrue(events.stream().noneMatch(AgentEvent.TurnRolledBack.class::isInstance));
        assertTrue(terminal(events).reason().contains("会话回滚失败"));
    }

    @Test
    void newTurnRetriesCompactionAfterPreviousFailure() throws Exception {
        ConversationManager conversation = new ConversationManager();
        conversation.addAssistantMessage("stable history");
        CompactRuntime runtime = CompactRuntime.create(tempDir);
        runtime.setContextWindow(81_000);
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.Error("summary unavailable")),
                List.of(new StreamEvent.TextDelta("<summary>recovered summary</summary>"),
                        new StreamEvent.StreamEnd("stop", 10, 5)),
                List.of(new StreamEvent.TextDelta("done"), new StreamEvent.StreamEnd("stop", 20, 5))));

        TurnCheckpoint first = TurnCheckpoint.capture(conversation);
        conversation.addUserMessage("a".repeat(4_000));
        drainUntilDone(new Agent(client, new ToolRegistry(), "dev", PermissionEngine.create(tempDir), runtime)
                .run(conversation, Mode.DEFAULT, new CancelToken(), first));

        TurnCheckpoint second = TurnCheckpoint.capture(conversation);
        conversation.addUserMessage("b".repeat(4_000));
        List<AgentEvent> secondEvents = drainUntilDone(new Agent(client, new ToolRegistry(), "dev",
                PermissionEngine.create(tempDir), runtime)
                .run(conversation, Mode.DEFAULT, new CancelToken(), second));

        assertEquals(3, client.calls);
        assertTrue(secondEvents.stream().anyMatch(AgentEvent.Compact.class::isInstance));
        assertEquals("done", conversation.getMessages().getLast().content());
    }

    @Test
    void unknownToolDoesNotPreventRollbackOnFollowingCompactFailure() throws Exception {
        String arguments = "{\"padding\":\"" + "x".repeat(30_000) + "\"}";
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("unknown", "MissingTool", arguments),
                        new StreamEvent.StreamEnd("tool_calls", 100, 10)),
                List.of(new StreamEvent.Error("summary unavailable"))));

        assertNonExecutedToolStillRollsBack(client, new ToolRegistry(), HookEngine.empty(), Outcome.DENY_ONCE);
    }

    @Test
    void permissionDenialDoesNotPreventRollbackOnFollowingCompactFailure() throws Exception {
        String arguments = "{\"path\":\"" + jsonPath(tempDir.resolve("denied.txt"))
                + "\",\"content\":\"" + "x".repeat(30_000) + "\"}";
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("denied", "WriteFile", arguments),
                        new StreamEvent.StreamEnd("tool_calls", 100, 10)),
                List.of(new StreamEvent.Error("summary unavailable"))));

        assertNonExecutedToolStillRollsBack(client, ToolRegistry.createDefault(), HookEngine.empty(), Outcome.DENY_ONCE);
    }

    @Test
    void compactFailureAfterToolKeepsTurnAndAddsAbortMarker() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FixedResultTool("large-small", false,
                ToolExecutionResult.ok("r".repeat(30_000))));
        ConversationManager conversation = new ConversationManager();
        TurnCheckpoint checkpoint = TurnCheckpoint.capture(conversation);
        conversation.addUserMessage("run tool");
        CompactRuntime runtime = CompactRuntime.create(tempDir);
        runtime.setContextWindow(88_000);
        List<List<StreamEvent>> scripts = new ArrayList<>();
        scripts.add(List.of(new StreamEvent.ToolCallComplete("call-1", "large-small", "{}"),
                new StreamEvent.StreamEnd("tool_calls", 100, 10)));
        for (int i = 0; i < 8; i++) {
            scripts.add(List.of(new StreamEvent.Error("prompt_too_long")));
        }

        BlockingQueue<AgentEvent> queue = new Agent(new FakeClient(scripts), registry, "dev",
                PermissionEngine.create(tempDir), runtime)
                .run(conversation, Mode.DEFAULT, new CancelToken(), checkpoint);
        List<AgentEvent> events = drainUntilDone(queue, Outcome.ALLOW_ONCE);

        assertTrue(conversation.getMessages().stream().anyMatch(message -> "tool".equals(message.role())));
        assertTrue(conversation.getMessages().getLast().content().contains("自动压缩上下文失败"));
        assertTrue(conversation.getMessages().getLast().content().contains("可重新发送消息重试"));
        assertTrue(events.stream().noneMatch(AgentEvent.TurnRolledBack.class::isInstance));
        assertEquals(TurnStatus.FAILED, terminal(events).status());
    }

    @Test
    void modelFailureAfterToolPersistsSpecificReasonInAssistantMarker() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FixedResultTool("read-ok", true, ToolExecutionResult.ok("result")));
        ConversationManager conversation = new ConversationManager();
        TurnCheckpoint checkpoint = TurnCheckpoint.capture(conversation);
        conversation.addUserMessage("run tool");
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("call-1", "read-ok", "{}"),
                        new StreamEvent.StreamEnd("tool_calls", 100, 10)),
                List.of(new StreamEvent.Error("gateway timeout"))));

        List<AgentEvent> events = drainUntilDone(new Agent(client, registry, "dev",
                PermissionEngine.create(tempDir), CompactRuntime.create(tempDir))
                .run(conversation, Mode.DEFAULT, new CancelToken(), checkpoint), Outcome.ALLOW_ONCE);

        assertTrue(conversation.getMessages().getLast().content().contains("gateway timeout"));
        assertEquals("gateway timeout", terminal(events).reason());
        assertEquals(TurnStatus.FAILED, terminal(events).status());
    }

    @Test
    void largeToolResultIsVisibleOnceThenOffloadedAfterFinalAssistant() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        String marker = "x".repeat(60_000) + "full-secret-marker";
        registry.register(new FixedResultTool("huge", false, ToolExecutionResult.ok(marker)));
        List<com.study.conversation.Message> appended = new ArrayList<>();
        List<List<com.study.conversation.Message>> replaced = new ArrayList<>();
        ConversationManager conversation = new ConversationManager(appended::add, replaced::add);
        TurnCheckpoint checkpoint = TurnCheckpoint.capture(conversation);
        conversation.addUserMessage("run huge");
        CompactRuntime runtime = CompactRuntime.create(tempDir);
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("call-1", "huge", "{}"),
                        new StreamEvent.StreamEnd("tool_calls", 100, 10)),
                List.of(new StreamEvent.TextDelta("done"), new StreamEvent.StreamEnd("stop", 200, 5))));

        BlockingQueue<AgentEvent> queue = new Agent(client, registry, "dev", PermissionEngine.create(tempDir), runtime)
                .run(conversation, Mode.DEFAULT, new CancelToken(), checkpoint);
        drainUntilDone(queue, Outcome.ALLOW_ONCE);

        assertTrue(client.requests.get(1).messages().stream().flatMap(message -> message.toolResults().stream())
                .anyMatch(result -> result.content().contains("full-secret-marker")));
        assertTrue(appended.stream().flatMap(message -> message.toolResults().stream())
                .anyMatch(result -> result.content().contains("[tool result compacted]")));
        assertTrue(appended.stream().flatMap(message -> message.toolResults().stream())
                .noneMatch(result -> result.content().contains("full-secret-marker")));
        assertTrue(conversation.getMessages().stream().flatMap(message -> message.toolResults().stream())
                .anyMatch(result -> result.content().contains("[tool result compacted]")));
        assertTrue(conversation.getMessages().stream().flatMap(message -> message.toolResults().stream())
                .noneMatch(result -> result.content().contains("full-secret-marker")));
        assertTrue(replaced.isEmpty());
        assertEquals("done", conversation.getMessages().getLast().content());
    }

    @Test
    void largeToolResultCleanupFailureDoesNotFailCompletedTurn() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FixedResultTool("huge-failure", true,
                ToolExecutionResult.ok("secret-large-body-" + "x".repeat(60_000))));
        Path blocker = tempDir.resolve("spill-blocker");
        Files.writeString(blocker, "not a directory");
        CompactRuntime runtime = new CompactRuntime(
                new com.study.compact.SessionContext("test", tempDir, blocker));
        ConversationManager conversation = new ConversationManager();
        TurnCheckpoint checkpoint = TurnCheckpoint.capture(conversation);
        conversation.addUserMessage("run huge failure");
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("call-1", "huge-failure", "{}"),
                        new StreamEvent.StreamEnd("tool_calls", 100, 10)),
                List.of(new StreamEvent.TextDelta("done"), new StreamEvent.StreamEnd("stop", 200, 5))));

        List<AgentEvent> events = drainUntilDone(new Agent(client, registry, "dev",
                PermissionEngine.create(tempDir), runtime)
                .run(conversation, Mode.DEFAULT, new CancelToken(), checkpoint), Outcome.ALLOW_ONCE);

        assertTrue(conversation.getMessages().stream().flatMap(message -> message.toolResults().stream())
                .anyMatch(result -> result.content().contains("secret-large-body")));
        assertEquals("done", conversation.getMessages().getLast().content());
        assertTrue(events.stream().noneMatch(AgentEvent.TurnRolledBack.class::isInstance));
        assertEquals(TurnStatus.SUCCEEDED, terminal(events).status());
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
            if (event instanceof AgentEvent.Finished) {
                return collected;
            }
        }
    }

    private String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private void assertNonExecutedToolStillRollsBack(FakeClient client, ToolRegistry registry,
                                                     HookEngine hooks, Outcome approval) throws Exception {
        ConversationManager conversation = new ConversationManager();
        conversation.addAssistantMessage("stable");
        TurnCheckpoint checkpoint = TurnCheckpoint.capture(conversation);
        conversation.addUserMessage("current request");
        CompactRuntime runtime = CompactRuntime.create(tempDir.resolve("non-executed-" + System.nanoTime()));
        runtime.setContextWindow(128_000);
        Agent agent = new Agent(client, registry, "dev", PermissionEngine.create(tempDir), runtime, "", null,
                null, List.of(), hooks, new HookRuntime());

        List<AgentEvent> events = drainUntilDone(
                agent.run(conversation, Mode.DEFAULT, new CancelToken(), checkpoint), approval);

        assertEquals(checkpoint.messagesBeforeTurn(), conversation.getMessages());
        assertTrue(events.stream().anyMatch(AgentEvent.TurnRolledBack.class::isInstance));
        assertEquals(TurnStatus.FAILED, terminal(events).status());
    }

    private AgentEvent.Finished terminal(List<AgentEvent> events) {
        List<AgentEvent.Finished> terminals = events.stream()
                .filter(AgentEvent.Finished.class::isInstance)
                .map(AgentEvent.Finished.class::cast)
                .toList();
        assertEquals(1, terminals.size());
        return terminals.getFirst();
    }

    private static List<String> toolNames(List<Map<String, Object>> tools) {
        return tools.stream().map(tool -> String.valueOf(tool.get("name"))).toList();
    }

    private void writeSkill(String name, String mode, String forkContext, String body) throws Exception {
        Path dir = tempDir.resolve(".code-agent/skills").resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: Demo skill
                mode: %s
                fork_context: %s
                ---

                %s
                """.formatted(name, mode, forkContext, body));
    }

    private static final class FakeClient implements LlmClient {
        private final List<List<StreamEvent>> scripts;
        private final boolean endlessUnknown;
        private int index;
        private int calls;
        private List<Map<String, Object>> lastTools = List.of();
        private final List<List<Map<String, Object>>> toolsByRequest = new ArrayList<>();
        private final List<Request> requests = new ArrayList<>();
        private Request lastRequest;

        private FakeClient(List<List<StreamEvent>> scripts) {
            this(scripts, false);
        }

        private FakeClient(List<List<StreamEvent>> scripts, boolean endlessUnknown) {
            this.scripts = scripts;
            this.endlessUnknown = endlessUnknown;
        }

        @Override
        public com.study.llm.LlmStream stream(Request request) {
            BlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
            calls++;
            lastRequest = request;
            requests.add(request);
            lastTools = List.copyOf(request.tools());
            toolsByRequest.add(lastTools);
            if (endlessUnknown) {
                queue.add(new StreamEvent.ToolCallComplete("unknown-" + calls, "MissingTool", "{}"));
                queue.add(new StreamEvent.StreamEnd("tool_calls", 0, 0));
            } else {
                scripts.get(index++).forEach(queue::add);
            }
            return new com.study.llm.TestLlmStream(queue);
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

    private record DeferredTestTool(String name, boolean readOnly) implements Tool {
        @Override
        public String description() {
            return name;
        }

        @Override
        public Map<String, Object> schema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public boolean shouldDefer() {
            return true;
        }

        @Override
        public ToolExecutionResult execute(Map<String, Object> args) {
            return ToolExecutionResult.ok(name + " done");
        }
    }

    private record FixedResultTool(String name, boolean readOnly, ToolExecutionResult result) implements Tool {
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
            return result;
        }
    }

    private record RegisteringResultTool(String name, boolean readOnly, ToolRegistry registry, Tool toolToRegister,
                                         ToolExecutionResult result) implements Tool {
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
            registry.register(toolToRegister);
            return result;
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
