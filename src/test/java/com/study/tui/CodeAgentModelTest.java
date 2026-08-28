package com.study.tui;

import com.study.config.ProviderConfig;
import com.study.compact.SessionContext;
import com.study.conversation.ConversationManager;
import com.study.conversation.ConversationPersistenceException;
import com.study.llm.LlmClient;
import com.study.llm.Request;
import com.study.llm.StreamEvent;
import com.study.llm.TestLlmStream;
import com.study.permission.Mode;
import com.study.permission.PermissionEngine;
import com.study.prompt.Reminder;
import com.study.session.SessionLoader;
import com.study.session.SessionWriter;
import com.study.skills.ActiveSkills;
import com.study.skills.Catalog;
import com.study.tool.Tool;
import com.study.tool.ToolExecutionResult;
import com.study.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeAgentModelTest {
    @TempDir
    Path tempDir;

    @Test
    void startsFromConfiguredModeAndPermissionSwitchIsRuntimeOnly() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path home = tempDir.resolve("home");
        Files.createDirectories(workspace.resolve(".code-agent"));
        Path settings = workspace.resolve(".code-agent/settings.yaml");
        Files.writeString(settings, "defaultMode: acceptEdits\n");
        PermissionEngine engine = PermissionEngine.create(workspace, home);
        CodeAgentModel model = new CodeAgentModel(List.of(provider("test", "openai", "test-model")),
                "system", new ToolRegistry(), engine, null, workspace);
        FakeCliIo io = new FakeCliIo();

        assertEquals(Mode.ACCEPT_EDITS, model.mode());
        model.submitLine("/permission bypassPermissions", io);

        assertEquals(Mode.BYPASS_PERMISSIONS, model.mode());
        assertEquals("defaultMode: acceptEdits\n", Files.readString(settings));
        assertTrue(io.transcript().contains("已切换权限模式: bypassPermissions"));
        model.close();
    }

    @Test
    void planLifecycleRestoresConfiguredPermissionMode() {
        CodeAgentModel model = model(new ToolRegistry());
        assertTrue(model.setPermissionMode(Mode.ACCEPT_EDITS));

        model.enterPlanMode();
        model.enterPlanMode();
        assertFalse(model.setPermissionMode(Mode.DEFAULT));
        model.exitPlanMode();

        assertEquals(Mode.ACCEPT_EDITS, model.mode());
        model.close();
    }

    @Test
    void synchronousTurnPrintsAssistantOnceAndReturnsIdle() throws Exception {
        CodeAgentModel model = model(new ToolRegistry());
        set(model, "client", new ScriptedClient(List.of(List.of(
                new StreamEvent.TextDelta("你"), new StreamEvent.TextDelta("好"),
                new StreamEvent.StreamEnd("stop", 2, 2)))));
        FakeCliIo io = new FakeCliIo();

        model.submitLine("hello", io);

        assertEquals("assistant: 你好" + System.lineSeparator(), io.transcript());
        assertTrue(model.idle());
        assertEquals(List.of("hello", "你好"), conversation(model).getMessages().stream()
                .map(com.study.conversation.Message::content).toList());
        model.close();
    }

    @Test
    void approvalRetriesInvalidInputAndPrintsOneToolStartAndEnd() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "change"; }
            @Override public String description() { return "change something"; }
            @Override public Map<String, Object> schema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public boolean readOnly() { return false; }
            @Override public ToolExecutionResult execute(Map<String, Object> args) {
                return ToolExecutionResult.ok("changed");
            }
        });
        CodeAgentModel model = model(registry);
        set(model, "client", new ScriptedClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("call-1", "change", "{}"),
                        new StreamEvent.StreamEnd("tool_calls", 2, 1)),
                List.of(new StreamEvent.TextDelta("done"), new StreamEvent.StreamEnd("stop", 3, 1)))));
        FakeCliIo io = new FakeCliIo().input("maybe").input("y");

        model.submitLine("change it", io);

        String transcript = io.transcript();
        assertEquals(1, occurrences(transcript, "审批: change"));
        assertEquals(2, occurrences(transcript, "请选择:"));
        assertTrue(transcript.contains("1. 允许本次"));
        assertTrue(transcript.contains("2. 拒绝本次"));
        assertEquals(1, occurrences(transcript, "tool: change({})"));
        assertEquals(1, occurrences(transcript, "tool: change 成功"));
        assertEquals(1, occurrences(transcript, "assistant: done"));
        model.close();
    }

    @Test
    void bashApprovalOffersPermanentAllowAndPersistsExactPersonalRule() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "Bash"; }
            @Override public String description() { return "test bash"; }
            @Override public Map<String, Object> schema() {
                return Map.of("type", "object", "properties", Map.of("command", Map.of("type", "string")));
            }
            @Override public boolean readOnly() { return false; }
            @Override public ToolExecutionResult execute(Map<String, Object> args) {
                executions.incrementAndGet();
                return ToolExecutionResult.ok("safe");
            }
        });
        Path workspace = tempDir.resolve("bash-workspace");
        Path home = tempDir.resolve("bash-home");
        Files.createDirectories(workspace);
        PermissionEngine engine = PermissionEngine.create(workspace, home);
        CodeAgentModel model = new CodeAgentModel(List.of(provider("test", "openai", "test-model")),
                "system", registry, engine, null, workspace);
        set(model, "client", new ScriptedClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("call-1", "Bash", "{\"command\":\"echo safe\"}"),
                        new StreamEvent.StreamEnd("tool_calls", 2, 1)),
                List.of(new StreamEvent.TextDelta("done"), new StreamEvent.StreamEnd("stop", 3, 1)))));
        FakeCliIo io = new FakeCliIo().input("2");

        model.submitLine("run", io);

        assertEquals(1, executions.get());
        assertTrue(io.transcript().contains("2. 永久允许"));
        assertTrue(io.transcript().contains("已将该 Bash 命令永久允许"));
        assertTrue(Files.readString(home.resolve(".code-agent/settings.yaml")).contains("Bash(echo safe)"));
        model.close();
    }

    @Test
    void injectedDoPersistsPromptButDoesNotPrintIt() throws Exception {
        CodeAgentModel model = model(new ToolRegistry());
        set(model, "client", new ScriptedClient(List.of(List.of(
                new StreamEvent.TextDelta("done"), new StreamEvent.StreamEnd("stop", 2, 1)))));
        FakeCliIo io = new FakeCliIo();

        model.enterPlanMode();
        model.submitLine("/do", io);

        assertEquals(Reminder.EXECUTE_DIRECTIVE, conversation(model).getMessages().getFirst().content());
        assertFalse(io.transcript().contains(Reminder.EXECUTE_DIRECTIVE));
        assertEquals("assistant: done" + System.lineSeparator(), io.transcript());
        model.close();
    }

    @Test
    void nonPlanDoDoesNotStartSessionOrCallModel() throws Exception {
        for (Mode mode : List.of(Mode.DEFAULT, Mode.ACCEPT_EDITS, Mode.BYPASS_PERMISSIONS)) {
            Path workspace = tempDir.resolve("non-plan-" + mode.name());
            CodeAgentModel model = new CodeAgentModel(List.of(provider("test", "openai", "test-model")),
                    "system", new ToolRegistry(), null, null, workspace);
            assertTrue(model.setPermissionMode(mode));
            ScriptedClient client = new ScriptedClient(List.of(List.of(
                    new StreamEvent.TextDelta("unexpected"), new StreamEvent.StreamEnd("stop", 1, 1))));
            set(model, "client", client);
            FakeCliIo io = new FakeCliIo();

            model.submitLine("/do", io);

            assertEquals(0, client.index);
            assertEquals(mode, model.mode());
            assertEquals("", model.sessionId());
            assertTrue(conversation(model).getMessages().isEmpty());
            assertTrue(io.transcript().contains("不在 Plan 模式"));
            model.close();
        }
    }

    @Test
    void planDoRestoresEveryPermissionModeAndPersistsDirective() throws Exception {
        for (Mode mode : List.of(Mode.DEFAULT, Mode.ACCEPT_EDITS, Mode.BYPASS_PERMISSIONS)) {
            Path workspace = tempDir.resolve("plan-do-" + mode.name());
            CodeAgentModel model = new CodeAgentModel(List.of(provider("test", "openai", "test-model")),
                    "system", new ToolRegistry(), null, null, workspace);
            assertTrue(model.setPermissionMode(mode));
            set(model, "client", new ScriptedClient(List.of(List.of(
                    new StreamEvent.TextDelta("done"), new StreamEvent.StreamEnd("stop", 2, 2)))));

            model.enterPlanMode();
            model.submitLine("/do", new FakeCliIo());

            assertEquals(mode, model.mode());
            assertFalse(model.sessionId().isBlank());
            assertEquals(Reminder.EXECUTE_DIRECTIVE,
                    SessionLoader.loadForResume(workspace.resolve(".code-agent/sessions")
                                    .resolve(model.sessionId())).getFirst().content());
            model.close();
        }
    }

    @Test
    void clearReturnsToLazyEmptySessionAndPreservesDiskMemory() throws Exception {
        CodeAgentModel model = model(new ToolRegistry());
        set(model, "client", new ScriptedClient(List.of(
                List.of(new StreamEvent.TextDelta("first"), new StreamEvent.StreamEnd("stop", 2, 2)),
                List.of(new StreamEvent.TextDelta("second"), new StreamEvent.StreamEnd("stop", 2, 2)))));
        FakeCliIo io = new FakeCliIo();
        model.submitLine("one", io);
        String oldId = model.sessionId();
        Path oldFile = tempDir.resolve(".code-agent/sessions").resolve(oldId).resolve("conversation.jsonl");
        long sessionCount = sessionDirectoryCount(tempDir);
        Path memory = tempDir.resolve(".code-agent/memory/MEMORY.md");
        Files.createDirectories(memory.getParent());
        Files.writeString(memory, "memory-sentinel");
        activeSkills(model).activate("demo", "body");
        set(model, "usageIn", 10L);
        set(model, "usageOut", 5L);
        set(model, "cacheWrite", 3L);
        set(model, "cacheRead", 2L);
        Object clientBeforeClear = get(model, "client");

        model.submitLine("/clear", io);

        assertEquals("", model.sessionId());
        assertTrue(conversation(model).getMessages().isEmpty());
        assertEquals(0, model.usageIn());
        assertEquals(0, model.usageOut());
        assertEquals(0L, get(model, "cacheWrite"));
        assertEquals(0L, get(model, "cacheRead"));
        assertTrue(model.listActiveSkills().isEmpty());
        assertEquals(Mode.DEFAULT, model.mode());
        assertTrue(clientBeforeClear == get(model, "client"));
        assertEquals("system", get(model, "systemPrompt"));
        assertEquals("memory-sentinel", Files.readString(memory));
        assertTrue(Files.isRegularFile(oldFile));
        assertEquals(List.of("one", "first"), SessionLoader.loadForResume(oldFile.getParent()).stream()
                .map(com.study.conversation.Message::content).toList());
        assertEquals(sessionCount, sessionDirectoryCount(tempDir));

        model.submitLine("/help", io);
        assertEquals(sessionCount, sessionDirectoryCount(tempDir));
        model.submitLine("two", io);
        assertNotEquals(oldId, model.sessionId());
        assertEquals(sessionCount + 1, sessionDirectoryCount(tempDir));
        model.close();
    }

    @Test
    void resumeWithoutArgumentsListsSessionsWithoutReadingAnotherInput() throws Exception {
        String older = createSession(tempDir, "older title", "old answer");
        Thread.sleep(20);
        String newer = createSession(tempDir, "newer title", "new answer");
        String corruptId = SessionContext.newSessionId();
        Path corruptDir = tempDir.resolve(".code-agent/sessions").resolve(corruptId);
        Files.createDirectories(corruptDir);
        Files.writeString(corruptDir.resolve("conversation.jsonl"), "list-secret-corrupt");
        CodeAgentModel model = model(new ToolRegistry());
        FakeCliIo io = new FakeCliIo();

        model.submitLine("/resume", io);

        String transcript = io.transcript();
        assertTrue(transcript.contains(older));
        assertTrue(transcript.contains(newer));
        assertTrue(transcript.indexOf(newer) < transcript.indexOf(older));
        assertTrue(transcript.contains("older title"));
        assertTrue(transcript.contains("test-model"));
        assertFalse(transcript.contains(corruptId));
        assertFalse(transcript.contains("list-secret-corrupt"));
        assertFalse(transcript.contains("选择编号"));
        assertEquals("", model.sessionId());
        model.close();
    }

    @Test
    void resumeByFullIdSwitchesWriterAndSameIdIsNoOp() throws Exception {
        String targetId = createSession(tempDir, "target question", "target answer");
        CodeAgentModel model = model(new ToolRegistry());
        set(model, "client", new ScriptedClient(List.of(List.of(
                new StreamEvent.TextDelta("continued"), new StreamEvent.StreamEnd("stop", 2, 2)))));
        FakeCliIo io = new FakeCliIo();

        model.submitLine("/resume " + targetId, io);
        assertEquals(targetId, model.sessionId());
        assertEquals(List.of("target question", "target answer"), conversation(model).getMessages().stream()
                .map(com.study.conversation.Message::content).toList());

        model.submitLine("/resume " + targetId, io);
        assertTrue(io.transcript().contains("已经处于活动状态"));
        assertEquals(2, conversation(model).size());

        model.submitLine("/resume", io);
        assertTrue(io.transcript().contains("* " + targetId));

        model.submitLine("continue", io);
        assertEquals(List.of("target question", "target answer", "continue", "continued"),
                SessionLoader.loadForResume(tempDir.resolve(".code-agent/sessions").resolve(targetId)).stream()
                        .map(com.study.conversation.Message::content).toList());
        model.close();
    }

    @Test
    void reviewForkRequestExposesOnlyConfiguredReadTools() throws Exception {
        Catalog catalog = Catalog.load(tempDir, Set.of());
        CodeAgentModel model = new CodeAgentModel(List.of(provider("test", "openai", "test-model")),
                "system", ToolRegistry.createDefault(), null, null, tempDir, null, null,
                catalog, new ActiveSkills());
        ScriptedClient client = new ScriptedClient(List.of(List.of(
                new StreamEvent.TextDelta("reviewed"), new StreamEvent.StreamEnd("stop", 2, 2))));
        set(model, "client", client);
        FakeCliIo io = new FakeCliIo();

        model.submitLine("/review", io);

        List<String> toolNames = client.requests.getFirst().tools().stream()
                .map(tool -> String.valueOf(tool.get("name")))
                .toList();
        assertEquals(List.of("ReadFile", "Glob", "Grep", "ToolSearch"), toolNames);
        assertFalse(toolNames.contains("Bash"));
        assertFalse(toolNames.contains("WriteFile"));
        assertFalse(toolNames.contains("EditFile"));
        model.close();
    }

    @Test
    void reviewRejectsForgedBashCallWithoutApprovalOrExecution() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "Bash"; }
            @Override public String description() { return "test bash"; }
            @Override public Map<String, Object> schema() {
                return Map.of("type", "object", "properties", Map.of("command", Map.of("type", "string")));
            }
            @Override public boolean readOnly() { return false; }
            @Override public ToolExecutionResult execute(Map<String, Object> args) {
                executions.incrementAndGet();
                return ToolExecutionResult.ok("should not execute");
            }
        });
        Path workspace = tempDir.resolve("review-forged-bash");
        Files.createDirectories(workspace.resolve(".code-agent"));
        Files.writeString(workspace.resolve(".code-agent/settings.yaml"),
                "permissions:\n  allow:\n    - Bash(echo forged)\n");
        PermissionEngine engine = PermissionEngine.create(workspace, tempDir.resolve("review-home"));
        Catalog catalog = Catalog.load(workspace, Set.of());
        CodeAgentModel model = new CodeAgentModel(List.of(provider("test", "openai", "test-model")),
                "system", registry, engine, null, workspace, null, null, catalog, new ActiveSkills());
        ScriptedClient client = new ScriptedClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("forged", "Bash", "{\"command\":\"echo forged\"}"),
                        new StreamEvent.StreamEnd("tool_calls", 2, 2)),
                List.of(new StreamEvent.TextDelta("reviewed safely"), new StreamEvent.StreamEnd("stop", 2, 2))));
        set(model, "client", client);
        FakeCliIo io = new FakeCliIo();

        model.submitLine("/review", io);

        assertEquals(0, executions.get());
        assertFalse(io.transcript().contains("审批:"));
        assertTrue(io.transcript().contains("reviewed safely"));
        model.close();
    }

    @Test
    void corruptedResumeTargetDoesNotReplaceCurrentSession() throws Exception {
        CodeAgentModel model = model(new ToolRegistry());
        set(model, "client", new ScriptedClient(List.of(
                List.of(new StreamEvent.TextDelta("original"), new StreamEvent.StreamEnd("stop", 2, 2)),
                List.of(new StreamEvent.TextDelta("continued"), new StreamEvent.StreamEnd("stop", 2, 2)))));
        FakeCliIo io = new FakeCliIo();
        model.submitLine("before", io);
        String originalId = model.sessionId();
        String corruptId = SessionContext.newSessionId();
        Path corruptDir = tempDir.resolve(".code-agent/sessions").resolve(corruptId);
        Files.createDirectories(corruptDir);
        Files.writeString(corruptDir.resolve("conversation.jsonl"), "fake-secret-corrupt-line");

        model.submitLine("/resume " + corruptId, io);
        assertEquals(originalId, model.sessionId());
        assertFalse(io.transcript().contains("fake-secret-corrupt-line"));
        model.submitLine("continue", io);

        assertEquals(List.of("before", "original", "continue", "continued"),
                SessionLoader.loadForResume(tempDir.resolve(".code-agent/sessions").resolve(originalId)).stream()
                        .map(com.study.conversation.Message::content).toList());
        model.close();
    }

    @Test
    void lockedResumeTargetFailsWithoutReplacingCurrentSession() throws Exception {
        String targetId = createSession(tempDir, "locked", "target");
        Path targetDir = tempDir.resolve(".code-agent/sessions").resolve(targetId);
        CodeAgentModel model = model(new ToolRegistry());
        set(model, "client", new ScriptedClient(List.of(
                List.of(new StreamEvent.TextDelta("original"), new StreamEvent.StreamEnd("stop", 2, 2)),
                List.of(new StreamEvent.TextDelta("after"), new StreamEvent.StreamEnd("stop", 2, 2)))));
        FakeCliIo io = new FakeCliIo();
        model.submitLine("before", io);
        String originalId = model.sessionId();

        try (SessionWriter ignored = SessionWriter.openForResume(targetDir, "test-model")) {
            model.submitLine("/resume " + targetId, io);
            assertEquals(originalId, model.sessionId());
            assertTrue(io.transcript().contains("恢复会话失败"));
            model.submitLine("after failure", io);
        }

        assertEquals(List.of("before", "original", "after failure", "after"),
                SessionLoader.loadForResume(tempDir.resolve(".code-agent/sessions").resolve(originalId)).stream()
                        .map(com.study.conversation.Message::content).toList());
        model.close();
    }

    @Test
    void resumeRejectsInvalidAndForeignSessionIds() throws Exception {
        Path foreign = tempDir.resolve("foreign");
        Files.createDirectories(foreign);
        String foreignId = createSession(foreign, "foreign secret", "answer");
        CodeAgentModel model = model(new ToolRegistry());
        FakeCliIo io = new FakeCliIo();

        model.submitLine("/resume 1", io);
        model.submitLine("/resume " + foreignId, io);

        assertEquals("", model.sessionId());
        assertTrue(io.transcript().contains("无效的完整 session ID"));
        assertTrue(io.transcript().contains("恢复会话失败"));
        assertFalse(io.transcript().contains("foreign secret"));
        model.close();
    }

    @Test
    void failureBeforeToolRollsBackAndPrintsSpecificReasonOnce() throws Exception {
        CodeAgentModel model = model(new ToolRegistry());
        set(model, "client", new ScriptedClient(List.of(List.of(new StreamEvent.Error("network unavailable")))));
        FakeCliIo io = new FakeCliIo();
        model.submitLine("/clear", io);
        conversation(model).addAssistantMessage("stable");
        int before = conversation(model).getMessages().size();

        model.submitLine("current request", io);

        assertEquals(before, conversation(model).getMessages().size());
        assertEquals("stable", conversation(model).getMessages().getLast().content());
        assertEquals(1, occurrences(io.transcript(), "network unavailable"));
        assertEquals(1, occurrences(io.transcript(), "本轮已回滚"));
        model.close();
    }

    @Test
    void userPersistenceFailureDoesNotStartAgent() throws Exception {
        CodeAgentModel model = model(new ToolRegistry());
        FakeCliIo io = new FakeCliIo();
        model.submitLine("/clear", io);
        ConversationManager failing = new ConversationManager(message -> {
            throw new ConversationPersistenceException("append failed", new java.io.IOException("disk full"));
        }, ignored -> { });
        set(model, "conversation", failing);

        model.submitLine("do work", io);

        assertTrue(failing.getMessages().isEmpty());
        assertEquals(1, occurrences(io.transcript(), "append failed"));
        assertTrue(model.idle());
        model.close();
    }

    @Test
    void providerSwitchKeepsConversationAndSession() throws Exception {
        CodeAgentModel model = new CodeAgentModel(List.of(
                provider("first", "openai", "m1"), provider("second", "anthropic", "m2")),
                "system", new ToolRegistry(), null, null, tempDir);
        FakeCliIo io = new FakeCliIo();
        model.submitLine("/clear", io);
        String sessionId = model.sessionId();
        ConversationManager conversation = conversation(model);

        model.submitLine("/model 2", io);

        assertEquals("m2", model.modelName());
        assertEquals(sessionId, model.sessionId());
        assertTrue(conversation == conversation(model));
        assertTrue(model.providers().get(1).active());
        assertTrue(io.transcript().contains("已切换到 second (m2)"));
        model.close();
    }

    private CodeAgentModel model(ToolRegistry registry) {
        return new CodeAgentModel(List.of(provider("test", "openai", "test-model")),
                "system", registry, null, null, tempDir);
    }

    private ProviderConfig provider(String name, String protocol, String model) {
        ProviderConfig provider = new ProviderConfig();
        provider.setName(name);
        provider.setProtocol(protocol);
        provider.setBaseUrl("http://127.0.0.1:1");
        provider.setApiKey("test-key");
        provider.setModel(model);
        provider.setContextWindow(100_000);
        return provider;
    }

    private ConversationManager conversation(CodeAgentModel model) throws Exception {
        Field field = CodeAgentModel.class.getDeclaredField("conversation");
        field.setAccessible(true);
        return (ConversationManager) field.get(model);
    }

    private ActiveSkills activeSkills(CodeAgentModel model) throws Exception {
        Field field = CodeAgentModel.class.getDeclaredField("activeSkills");
        field.setAccessible(true);
        return (ActiveSkills) field.get(model);
    }

    private long sessionDirectoryCount(Path workspace) throws Exception {
        Path root = workspace.resolve(".code-agent/sessions");
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isDirectory).count();
        }
    }

    private String createSession(Path workspace, String user, String assistant) throws Exception {
        SessionContext context = SessionContext.create(workspace);
        try (SessionWriter writer = SessionWriter.create(context.sessionDir(), "test-model")) {
            writer.append(com.study.conversation.Message.user(user));
            writer.append(com.study.conversation.Message.assistant(assistant));
        }
        return context.sessionId();
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private int occurrences(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static final class ScriptedClient implements LlmClient {
        private final List<List<StreamEvent>> scripts;
        private final java.util.ArrayList<Request> requests = new java.util.ArrayList<>();
        private int index;

        private ScriptedClient(List<List<StreamEvent>> scripts) {
            this.scripts = scripts;
        }

        @Override
        public com.study.llm.LlmStream stream(Request request) {
            requests.add(request);
            return new TestLlmStream(scripts.get(index++));
        }

        @Override public String name() { return "scripted"; }
        @Override public String model() { return "scripted-model"; }
    }
}
