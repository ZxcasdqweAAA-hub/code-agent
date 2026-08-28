package com.study.command;

import com.study.permission.Mode;
import com.study.conversation.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinsTest {
    @Test
    void registerAllRegistersExpectedCommands() {
        CommandRegistry registry = new CommandRegistry();

        Builtins.registerAll(registry);

        assertEquals(List.of("clear", "do", "exit", "help", "memory", "model", "permission", "plan", "resume", "session", "skill", "status", "team", "worktree"),
                registry.visible().stream().map(Command::name).toList());
    }

    @Test
    void localHandlersPrintExpectedInformation() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        Builtins.registerAll(registry);
        RecordingUi ui = new RecordingUi();

        registry.lookup("status").orElseThrow().handler().handle(null, ui, "");
        registry.lookup("session").orElseThrow().handler().handle(null, ui, "");

        assertTrue(ui.printed.stream().anyMatch(text -> text.contains("Mode:") && text.contains("Tokens:")));
        assertTrue(ui.printed.stream().anyMatch(text -> text.contains("Session:")));
    }

    @Test
    void doCommandSwitchesToNormalAndInjectsPrompt() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        Builtins.registerAll(registry);
        RecordingUi ui = new RecordingUi();
        ui.mode = Mode.PLAN;

        registry.lookup("do").orElseThrow().handler().handle(null, ui, "");

        assertEquals(Mode.DEFAULT, ui.mode);
        assertEquals("/do", ui.injectedDisplay);
        assertTrue(ui.injectedPrompt.contains("execute"));
    }

    @Test
    void permissionCommandShowsSwitchesAndRejectsChangesInPlan() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        Builtins.registerAll(registry);
        RecordingUi ui = new RecordingUi();

        registry.lookup("permission").orElseThrow().handler().handle(null, ui, "");
        registry.lookup("permission").orElseThrow().handler().handle(null, ui, "AcCePtEdItS");
        assertEquals(Mode.ACCEPT_EDITS, ui.mode);
        assertTrue(ui.printed.stream().anyMatch(text -> text.contains("可用模式")));

        ui.enterPlanMode();
        registry.lookup("permission").orElseThrow().handler().handle(null, ui, "default");
        assertEquals(Mode.PLAN, ui.mode);
        assertTrue(ui.printed.stream().anyMatch(text -> text.contains("先使用 /do")));
    }

    @Test
    void planAndDoRestoreModeSavedBeforeFirstPlanEntry() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        Builtins.registerAll(registry);
        RecordingUi ui = new RecordingUi();
        ui.mode = Mode.ACCEPT_EDITS;
        ui.modeBeforePlan = Mode.ACCEPT_EDITS;

        registry.lookup("plan").orElseThrow().handler().handle(null, ui, "");
        registry.lookup("plan").orElseThrow().handler().handle(null, ui, "");
        registry.lookup("do").orElseThrow().handler().handle(null, ui, "");

        assertEquals(Mode.ACCEPT_EDITS, ui.mode);
    }

    @Test
    void nonPlanDoIsRejectedWithoutInjectingPrompt() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        Builtins.registerAll(registry);
        for (Mode mode : List.of(Mode.DEFAULT, Mode.ACCEPT_EDITS, Mode.BYPASS_PERMISSIONS)) {
            RecordingUi ui = new RecordingUi();
            ui.mode = mode;

            registry.lookup("do").orElseThrow().handler().handle(null, ui, "");

            assertEquals(mode, ui.mode);
            assertEquals("", ui.injectedDisplay);
            assertTrue(ui.printed.stream().anyMatch(text -> text.contains("不在 Plan 模式")));
        }
    }

    @Test
    void resumePassesTrimmedArgumentAndClearUsesSingleEntryPoint() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        Builtins.registerAll(registry);
        RecordingUi ui = new RecordingUi();

        registry.lookup("resume").orElseThrow().handler().handle(null, ui, "  session-id  ");
        assertEquals("session-id", ui.resumedSessionId);

        registry.lookup("resume").orElseThrow().handler().handle(null, ui, "");
        assertEquals("", ui.resumedSessionId);

        registry.lookup("clear").orElseThrow().handler().handle(null, ui, "");
        assertEquals(1, ui.clearCalls);
        assertEquals(0, ui.clearActiveSkillCalls);
    }

    private static final class RecordingUi implements Ui {
        private final List<String> printed = new ArrayList<>();
        private Mode mode = Mode.DEFAULT;
        private Mode modeBeforePlan = Mode.DEFAULT;
        private String injectedDisplay = "";
        private String injectedPrompt = "";
        private String resumedSessionId;
        private int clearCalls;
        private int clearActiveSkillCalls;

        @Override
        public void println(String msg) {
            printed.add(msg);
        }

        @Override
        public void error(String msg) {
            printed.add(msg);
        }

        @Override
        public Mode mode() {
            return mode;
        }

        @Override
        public boolean setPermissionMode(Mode mode) {
            if (this.mode == Mode.PLAN || mode == null || !mode.configurable()) {
                return false;
            }
            this.mode = mode;
            this.modeBeforePlan = mode;
            return true;
        }

        @Override
        public void enterPlanMode() {
            if (mode != Mode.PLAN) {
                modeBeforePlan = mode;
                mode = Mode.PLAN;
            }
        }

        @Override
        public void exitPlanMode() {
            if (mode == Mode.PLAN) {
                mode = modeBeforePlan;
            }
        }

        @Override
        public void injectAndSend(String displayText, String presetPrompt) {
            injectedDisplay = displayText;
            injectedPrompt = presetPrompt;
        }

        @Override
        public long usageIn() {
            return 12;
        }

        @Override
        public long usageOut() {
            return 3;
        }

        @Override
        public String modelName() {
            return "model";
        }

        @Override
        public String cwd() {
            return "cwd";
        }

        @Override
        public int toolCount() {
            return 2;
        }

        @Override
        public List<String> memoryFiles() {
            return List.of("MEMORY.md");
        }

        @Override
        public String sessionPath() {
            return "conversation.jsonl";
        }

        @Override
        public String sessionId() {
            return "session-1";
        }

        @Override
        public void quit() {
        }

        @Override
        public void resumeSession(String sessionId) {
            resumedSessionId = sessionId;
        }

        @Override
        public void clearAndNewSession() {
            clearCalls++;
        }

        @Override
        public List<SkillSummary> listCatalogSkills() {
            return List.of();
        }

        @Override
        public List<String> listActiveSkills() {
            return List.of();
        }

        @Override
        public void clearActiveSkills() {
            clearActiveSkillCalls++;
        }

        @Override
        public void appendAssistantMessage(String text) {
        }

        @Override
        public List<Message> recentMessages(int count) {
            return List.of();
        }

        @Override
        public boolean idle() {
            return true;
        }

        @Override
        public List<ProviderSummary> providers() {
            return List.of();
        }

        @Override
        public ModelSwitchResult switchProvider(String selector) {
            return new ModelSwitchResult(false, "not available");
        }

        @Override
        public WorktreeAccessor worktreeAccessor() {
            return null;
        }

        @Override
        public TeamAccessor teamAccessor() {
            return null;
        }
    }
}
