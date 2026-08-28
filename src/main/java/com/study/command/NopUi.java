package com.study.command;

import com.study.permission.Mode;
import com.study.conversation.Message;

import java.util.List;

public final class NopUi implements Ui {
    public static final NopUi INSTANCE = new NopUi();

    private NopUi() {
    }

    @Override
    public void println(String msg) {
    }

    @Override
    public void error(String msg) {
    }

    @Override
    public Mode mode() {
        return Mode.DEFAULT;
    }

    @Override
    public boolean setPermissionMode(Mode mode) {
        return mode != null && mode.configurable();
    }

    @Override
    public void enterPlanMode() {
    }

    @Override
    public void exitPlanMode() {
    }

    @Override
    public void injectAndSend(String displayText, String presetPrompt) {
    }

    @Override
    public long usageIn() {
        return 0;
    }

    @Override
    public long usageOut() {
        return 0;
    }

    @Override
    public String modelName() {
        return "";
    }

    @Override
    public String cwd() {
        return "";
    }

    @Override
    public int toolCount() {
        return 0;
    }

    @Override
    public List<String> memoryFiles() {
        return List.of();
    }

    @Override
    public String sessionPath() {
        return "";
    }

    @Override
    public String sessionId() {
        return "";
    }

    @Override
    public void quit() {
    }

    @Override
    public void resumeSession(String sessionId) {
    }

    @Override
    public void clearAndNewSession() {
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
        return new ModelSwitchResult(false, "provider switching is unavailable");
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
