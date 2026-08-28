package com.study.command;

import com.study.permission.Mode;
import com.study.conversation.Message;

import java.util.List;

public interface Ui {
    void println(String msg);

    void error(String msg);

    Mode mode();

    boolean setPermissionMode(Mode mode);

    void enterPlanMode();

    void exitPlanMode();

    void injectAndSend(String displayText, String presetPrompt);

    long usageIn();

    long usageOut();

    String modelName();

    String cwd();

    int toolCount();

    List<String> memoryFiles();

    String sessionPath();

    String sessionId();

    void quit();

    void resumeSession(String sessionId);

    void clearAndNewSession();

    List<SkillSummary> listCatalogSkills();

    List<String> listActiveSkills();

    void clearActiveSkills();

    void appendAssistantMessage(String text);

    List<Message> recentMessages(int count);

    boolean idle();

    List<ProviderSummary> providers();

    ModelSwitchResult switchProvider(String selector);

    WorktreeAccessor worktreeAccessor();

    TeamAccessor teamAccessor();
}
