package com.study.tui;

import com.study.agent.Agent;
import com.study.agent.AgentEvent;
import com.study.agent.ApprovalRequest;
import com.study.agent.CancelToken;
import com.study.permission.Mode;
import com.study.agent.Phase;
import com.study.agent.TurnCheckpoint;
import com.study.agent.TurnStatus;
import com.study.command.Builtins;
import com.study.command.CommandRegistry;
import com.study.command.Dispatch;
import com.study.command.ModelSwitchResult;
import com.study.command.ProviderSummary;
import com.study.command.SkillSummary;
import com.study.command.Skills;
import com.study.command.TeamAccessor;
import com.study.command.Ui;
import com.study.command.WorktreeAccessor;
import com.study.compact.CompactRuntime;
import com.study.compact.SessionContext;
import com.study.config.ProviderConfig;
import com.study.conversation.ConversationManager;
import com.study.conversation.ConversationPersistenceException;
import com.study.hook.HookEngine;
import com.study.hook.HookRuntime;
import com.study.llm.LlmClient;
import com.study.memory.MemoryManager;
import com.study.permission.Outcome;
import com.study.permission.PermissionEngine;
import com.study.prompt.PromptBuilder;
import com.study.prompt.SkillsBlock;
import com.study.session.SessionInfo;
import com.study.session.SessionList;
import com.study.session.SessionLoader;
import com.study.session.SessionWriter;
import com.study.skills.ActiveSkills;
import com.study.skills.Catalog;
import com.study.skills.Executor;
import com.study.skills.Skill;
import com.study.task.BackgroundTask;
import com.study.task.Manager;
import com.study.team.Team;
import com.study.team.TeamManager;
import com.study.team.TeammateInfo;
import com.study.tool.ToolRegistry;
import com.study.worktree.ExitAction;
import com.study.worktree.ExitOptions;
import com.study.worktree.Worktree;
import com.study.worktree.WorktreeManager;
import com.study.worktree.WorktreeSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class CodeAgentModel implements Ui, AutoCloseable {
    private static final Pattern SESSION_ID = Pattern.compile("\\d{8}-\\d{6}-[0-9a-f]{4}");
    private static final DateTimeFormatter SESSION_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private final List<ProviderConfig> providers;
    private volatile String systemPrompt;
    private final String instructionText;
    private final ToolRegistry toolRegistry;
    private final PermissionEngine permissions;
    private final MemoryManager memoryManager;
    private final Catalog skillCatalog;
    private final ActiveSkills activeSkills;
    private final Executor skillExecutor;
    private volatile Skill pendingInlineSkill;
    private final HookEngine hookEngine;
    private final HookRuntime hookRuntime;
    private final Manager taskManager;
    private final WorktreeManager worktreeManager;
    private final TeamManager teamManager;
    private final CommandRegistry cmdRegistry = new CommandRegistry();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Path workspace;

    private CompactRuntime compactRuntime;
    private ConversationManager conversation;
    private Path activeCwd;
    private SessionContext sessionContext;
    private SessionWriter sessionWriter;
    private int selectedProvider;
    private LlmClient client;
    private volatile boolean streaming;
    private BlockingQueue<AgentEvent> agentQueue;
    private Mode mode;
    private Mode modeBeforePlan;
    private int iter;
    private long usageIn;
    private long usageOut;
    private long cacheWrite;
    private long cacheRead;
    private volatile CancelToken turnCancel;
    private volatile boolean quitRequested;
    private volatile CliIo currentIo;

    public CodeAgentModel(List<ProviderConfig> providers, String systemPrompt) {
        this(providers, systemPrompt, ToolRegistry.createDefault());
    }

    public CodeAgentModel(List<ProviderConfig> providers, String systemPrompt, ToolRegistry toolRegistry) {
        this(providers, systemPrompt, toolRegistry, PermissionEngine.create(Path.of("")));
    }

    public CodeAgentModel(List<ProviderConfig> providers, String systemPrompt, ToolRegistry toolRegistry,
                          PermissionEngine permissions) {
        this(providers, systemPrompt, toolRegistry, permissions, null);
    }

    public CodeAgentModel(List<ProviderConfig> providers, String systemPrompt, ToolRegistry toolRegistry,
                          PermissionEngine permissions, CompactRuntime compactRuntime) {
        this(providers, systemPrompt, toolRegistry, permissions, compactRuntime, Path.of(""));
    }

    public CodeAgentModel(List<ProviderConfig> providers, String systemPrompt, ToolRegistry toolRegistry,
                          PermissionEngine permissions, CompactRuntime compactRuntime, Path workspace) {
        this(providers, systemPrompt, toolRegistry, permissions, compactRuntime, workspace, null);
    }

    public CodeAgentModel(List<ProviderConfig> providers, String systemPrompt, ToolRegistry toolRegistry,
                          PermissionEngine permissions, CompactRuntime compactRuntime, Path workspace,
                          MemoryManager memoryManager) {
        this(providers, systemPrompt, toolRegistry, permissions, compactRuntime, workspace, memoryManager, null);
    }

    public CodeAgentModel(List<ProviderConfig> providers, String systemPrompt, ToolRegistry toolRegistry,
                          PermissionEngine permissions, CompactRuntime compactRuntime, Path workspace,
                          MemoryManager memoryManager, String instructionText) {
        this(providers, systemPrompt, toolRegistry, permissions, compactRuntime, workspace, memoryManager,
                instructionText, null, null);
    }

    public CodeAgentModel(List<ProviderConfig> providers, String systemPrompt, ToolRegistry toolRegistry,
                          PermissionEngine permissions, CompactRuntime compactRuntime, Path workspace,
                          MemoryManager memoryManager, String instructionText,
                          Catalog skillCatalog, ActiveSkills activeSkills) {
        this(providers, systemPrompt, toolRegistry, permissions, compactRuntime, workspace, memoryManager,
                instructionText, skillCatalog, activeSkills, HookEngine.empty(), new HookRuntime(), null, null, null);
    }

    public CodeAgentModel(List<ProviderConfig> providers, String systemPrompt, ToolRegistry toolRegistry,
                          PermissionEngine permissions, CompactRuntime compactRuntime, Path workspace,
                          MemoryManager memoryManager, String instructionText,
                          Catalog skillCatalog, ActiveSkills activeSkills,
                          HookEngine hookEngine, HookRuntime hookRuntime, Manager taskManager,
                          WorktreeManager worktreeManager, TeamManager teamManager) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.instructionText = instructionText;
        this.toolRegistry = toolRegistry == null ? ToolRegistry.createDefault() : toolRegistry;
        this.permissions = permissions == null ? PermissionEngine.create(Path.of("")) : permissions;
        this.mode = this.permissions.startMode();
        this.modeBeforePlan = this.mode;
        this.compactRuntime = compactRuntime;
        this.workspace = workspace == null ? Path.of("").toAbsolutePath().normalize()
                : workspace.toAbsolutePath().normalize();
        this.memoryManager = memoryManager;
        this.skillCatalog = skillCatalog;
        this.activeSkills = activeSkills == null ? new ActiveSkills() : activeSkills;
        this.hookEngine = hookEngine == null ? HookEngine.empty() : hookEngine;
        this.hookRuntime = hookRuntime == null ? new HookRuntime() : hookRuntime;
        this.taskManager = taskManager;
        this.worktreeManager = worktreeManager;
        this.teamManager = teamManager;
        this.activeCwd = activeCwdFromSession(worktreeManager);
        this.sessionContext = compactRuntime == null ? null : compactRuntime.session();
        this.conversation = new ConversationManager(
                this::appendSession, this::replaceSession, this::truncateSession);
        this.skillExecutor = skillCatalog == null ? null : new Executor(skillCatalog, this::runForkSkill);
        Builtins.registerAll(cmdRegistry);
        if (skillCatalog != null) {
            Skills.registerSkillsAsCommands(cmdRegistry, skillSummaries(), this::executeSkillCommand);
        }
        if (!this.providers.isEmpty()) {
            initializeProvider(0);
        }
        if (memoryManager != null && instructionText != null) {
            memoryManager.onUpdated(this::refreshSystemPromptFromMemory);
        }
        subscribeTaskNotifications();
        subscribeLeadMail();
    }

    public void submitLine(String text, CliIo io) {
        if (text == null || text.isBlank() || io == null) {
            return;
        }
        if (!idle()) {
            io.println("错误: 请等待当前任务完成");
            return;
        }
        currentIo = io;
        try {
            Dispatch.Parsed parsed = Dispatch.parse(text);
            if (!parsed.isSlash()) {
                executeTurn(TurnInput.plain(text), io);
                return;
            }
            var command = cmdRegistry.lookup(parsed.name());
            if (command.isEmpty()) {
                io.println(parsed.name().isBlank()
                        ? "未知命令。输入 /help 查看可用命令"
                        : "未知命令: /" + parsed.name() + "。输入 /help 查看可用命令");
                return;
            }
            try {
                command.get().handler().handle(cancelled, this, parsed.args());
            } catch (Exception e) {
                error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        } finally {
            currentIo = null;
        }
    }

    private void executeSkillCommand(Ui ui, String name, String args) {
        Skill skill = skillCatalog == null ? null : skillCatalog.get(name).orElse(null);
        pendingInlineSkill = skill != null && !skill.meta().isFork() ? skill : null;
        if (skillExecutor != null) {
            skillExecutor.execute(ui, name, args);
        }
    }

    private void executeTurn(TurnInput turn, CliIo io) {
        if (!ensureSessionStarted()) {
            return;
        }
        TurnCheckpoint checkpoint = TurnCheckpoint.capture(conversation);
        try {
            conversation.addUserMessage(turn.promptText());
        } catch (ConversationPersistenceException e) {
            error(e.getMessage());
            return;
        }
        streaming = true;
        iter = 0;
        turnCancel = new CancelToken();
        Agent agent = new Agent(client, toolRegistry, "0.1.0", permissions, compactRuntime, systemPrompt,
                memoryManager, activeSkills, List.of(), hookEngine, hookRuntime,
                0, false, false, "", effectiveCwd(), sessionId());
        Skill skill = pendingInlineSkill;
        pendingInlineSkill = null;
        if (skill != null && !skill.meta().isFork()) {
            agent.preloadSkillTools(skill);
        }
        agentQueue = agent.run(conversation, mode, turnCancel, checkpoint);
        if (io instanceof JLineCliIo jline) {
            jline.setActiveCancel(turnCancel);
        }
        try {
            consumeTurn(io);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            turnCancel.cancel();
            io.println("已中断当前任务。");
        } finally {
            if (io instanceof JLineCliIo jline) {
                jline.clearActiveCancel();
            }
            finishTurn();
        }
    }

    private void consumeTurn(CliIo io) throws InterruptedException {
        boolean assistantLine = false;
        String lastStatus = "";
        while (true) {
            AgentEvent event = agentQueue.take();
            if (event instanceof AgentEvent.Text text) {
                if (!assistantLine) {
                    io.print("assistant: ");
                    assistantLine = true;
                }
                io.print(text.delta());
                io.flush();
            } else if (event instanceof AgentEvent.Tool tool) {
                assistantLine = endAssistantLine(io, assistantLine);
                if (tool.event().phase() == Phase.START) {
                    io.println("tool: " + tool.event().name() + "(" + tool.event().argumentsPreview() + ")");
                } else {
                    String state = tool.event().error() ? "失败" : "成功";
                    String preview = tool.event().result() == null || tool.event().result().isBlank()
                            ? "" : " — " + tool.event().result();
                    io.println("tool: " + tool.event().name() + " " + state + preview);
                }
            } else if (event instanceof AgentEvent.Approval approval) {
                assistantLine = endAssistantLine(io, assistantLine);
                ApprovalRequest request = approval.request();
                io.println("审批: " + request.name() + "(" + cliPreview(request.arguments()) + ")");
                if (request.reason() != null && !request.reason().isBlank()) {
                    io.println("原因: " + request.reason());
                }
                if (io instanceof JLineCliIo jline) {
                    jline.clearActiveCancel();
                }
                request.respond().offer(readApproval(io, request, turnCancel));
                if (io instanceof JLineCliIo jline && !turnCancel.isCancelled()) {
                    jline.setActiveCancel(turnCancel);
                }
            } else if (event instanceof AgentEvent.UsageReport usage) {
                usageIn += usage.inputTokens();
                usageOut += usage.outputTokens();
                cacheWrite += usage.cacheWrite();
                cacheRead += usage.cacheRead();
            } else if (event instanceof AgentEvent.Iter currentIter) {
                iter = currentIter.value();
            } else if (event instanceof AgentEvent.Notice notice) {
                assistantLine = endAssistantLine(io, assistantLine);
                lastStatus = notice.message();
                io.println(notice.message());
            } else if (event instanceof AgentEvent.Compact compact) {
                assistantLine = endAssistantLine(io, assistantLine);
                lastStatus = compact.message();
                io.println(compact.message());
            } else if (event instanceof AgentEvent.TurnRolledBack rolledBack) {
                assistantLine = endAssistantLine(io, assistantLine);
                lastStatus = rolledBack.message();
                io.println(rolledBack.message());
            } else if (event instanceof AgentEvent.Finished finished) {
                endAssistantLine(io, assistantLine);
                if (finished.status() != TurnStatus.SUCCEEDED && !finished.reason().isBlank()
                        && !finished.reason().equals(lastStatus)) {
                    io.println(finished.reason());
                }
                return;
            }
        }
    }

    private boolean endAssistantLine(CliIo io, boolean open) {
        if (open) {
            io.println("");
        }
        return false;
    }

    private String cliPreview(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 117) + "...";
    }

    private Outcome readApproval(CliIo io, ApprovalRequest request, CancelToken cancel) {
        if (request.allowForever()) {
            io.println("1. 允许本次");
            io.println("2. 永久允许（写入个人级配置）");
            io.println("3. 拒绝本次");
        } else {
            io.println("1. 允许本次");
            io.println("2. 拒绝本次");
        }
        while (cancel == null || !cancel.isCancelled()) {
            try {
                String value = io.readLine("请选择: ").strip().toLowerCase();
                if (value.equals("1") || value.equals("y") || value.equals("yes")) {
                    return Outcome.ALLOW_ONCE;
                }
                if (request.allowForever() && value.equals("2")) {
                    return Outcome.ALLOW_FOREVER;
                }
                if ((request.allowForever() && value.equals("3"))
                        || (!request.allowForever() && value.equals("2"))
                        || value.equals("n") || value.equals("no")) {
                    return Outcome.DENY_ONCE;
                }
                io.println(request.allowForever()
                        ? "请输入 1、2、3，或使用 y/n。"
                        : "请输入 1、2，或使用 y/n。");
            } catch (CliInterruptedException | CliEofException e) {
                return Outcome.DENY_ONCE;
            }
        }
        return Outcome.DENY_ONCE;
    }

    public void cancelActiveTurn() {
        CancelToken active = turnCancel;
        if (active != null) {
            active.cancel();
        }
    }

    public List<String> completionNames() {
        return cmdRegistry.names();
    }

    public boolean quitRequested() {
        return quitRequested;
    }

    public String banner() {
        return """
                  /\\_/\\\\
                 ( o.o )
                  > ^ <
                Code Agent v0.1.0
                cwd: %s
                """.formatted(workspace);
    }

    private void initializeProvider(int index) {
        selectedProvider = index;
        client = LlmClient.create(providers.get(index), systemPrompt);
        if (compactRuntime != null) {
            compactRuntime.setContextWindow(providers.get(index).effectiveContextWindow());
        }
        if (memoryManager != null) {
            memoryManager.setClient(client);
        }
    }

    private boolean ensureSessionStarted() {
        if (client == null) {
            error("provider 未就绪");
            return false;
        }
        if (sessionContext != null) {
            if (compactRuntime == null) {
                compactRuntime = new CompactRuntime(sessionContext);
            }
            compactRuntime.setContextWindow(providers.get(selectedProvider).effectiveContextWindow());
            if (sessionWriter == null) {
                openSessionWriter();
            }
            if (sessionWriter == null) {
                error("无法打开 session");
                return false;
            }
            return true;
        }
        try {
            sessionContext = SessionContext.create(workspace);
            compactRuntime = new CompactRuntime(sessionContext);
            compactRuntime.setContextWindow(providers.get(selectedProvider).effectiveContextWindow());
            sessionWriter = SessionWriter.create(sessionContext.sessionDir(), client.model());
            return true;
        } catch (Exception e) {
            sessionContext = null;
            compactRuntime = null;
            sessionWriter = null;
            error("创建 session 失败: " + e.getMessage());
            return false;
        }
    }

    private synchronized void refreshSystemPromptFromMemory() {
        if (instructionText == null || memoryManager == null) {
            return;
        }
        systemPrompt = PromptBuilder.buildSystemPrompt(
                instructionText, memoryManager.loadPromptContext(), skillsCatalogText());
        if (client != null && selectedProvider >= 0 && selectedProvider < providers.size()) {
            client = LlmClient.create(providers.get(selectedProvider), systemPrompt);
            memoryManager.setClient(client);
        }
    }

    private void openSessionWriter() {
        if (sessionContext == null || client == null) {
            return;
        }
        closeSessionWriter();
        try {
            sessionWriter = SessionWriter.open(sessionContext.sessionDir(), client.model());
        } catch (Exception e) {
            sessionWriter = null;
        }
    }

    private void closeSessionWriter() {
        SessionWriter current = sessionWriter;
        sessionWriter = null;
        closeWriterQuietly(current);
    }

    private void closeWriterQuietly(SessionWriter writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (Exception e) {
            System.err.println("关闭 session writer 失败: " + e.getMessage());
        }
    }

    private void appendSession(com.study.conversation.Message message) {
        if (sessionWriter == null) {
            return;
        }
        try {
            sessionWriter.append(message);
        } catch (java.io.IOException e) {
            throw new ConversationPersistenceException("追加会话消息失败", e);
        }
    }

    private void replaceSession(List<com.study.conversation.Message> messages) {
        if (sessionWriter == null) {
            return;
        }
        try {
            sessionWriter.replace(messages);
        } catch (java.io.IOException e) {
            throw new ConversationPersistenceException("写入会话快照失败", e);
        }
    }

    private void truncateSession(com.study.conversation.ConversationTruncation truncation) {
        if (sessionWriter == null) {
            return;
        }
        try {
            sessionWriter.truncate(truncation);
        } catch (java.io.IOException e) {
            throw new ConversationPersistenceException("写入会话截断标记失败", e);
        }
    }

    @Override
    public void println(String msg) {
        CliIo io = currentIo;
        if (io != null) {
            io.println(msg == null ? "" : msg);
        }
    }

    @Override
    public void error(String msg) {
        CliIo io = currentIo;
        if (io != null) {
            io.println("错误: " + (msg == null ? "" : msg));
        } else {
            System.err.println("错误: " + (msg == null ? "" : msg));
        }
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
            mode = modeBeforePlan == null || !modeBeforePlan.configurable()
                    ? Mode.DEFAULT
                    : modeBeforePlan;
        }
    }

    @Override
    public void injectAndSend(String displayText, String presetPrompt) {
        if (currentIo == null) {
            error("当前没有可用的 CLI 输入上下文");
            return;
        }
        executeTurn(TurnInput.injected(displayText, presetPrompt), currentIo);
    }

    @Override
    public long usageIn() {
        return usageIn;
    }

    @Override
    public long usageOut() {
        return usageOut;
    }

    @Override
    public String modelName() {
        return client == null ? "" : client.model();
    }

    @Override
    public String cwd() {
        return effectiveCwd().toString();
    }

    @Override
    public int toolCount() {
        return toolRegistry.count();
    }

    @Override
    public List<String> memoryFiles() {
        return memoryManager == null ? List.of() : memoryManager.listFiles();
    }

    @Override
    public String sessionPath() {
        return sessionWriter == null ? "" : sessionWriter.file().toString();
    }

    @Override
    public String sessionId() {
        return sessionContext == null ? "" : sessionContext.sessionId();
    }

    @Override
    public void quit() {
        cancelled.set(true);
        cancelActiveTurn();
        quitRequested = true;
    }

    @Override
    public void resumeSession(String sessionId) {
        String requested = sessionId == null ? "" : sessionId.strip();
        if (requested.isBlank()) {
            listSessions();
            return;
        }
        if (!SESSION_ID.matcher(requested).matches()) {
            error("无效的完整 session ID: " + requested);
            return;
        }
        try {
            SessionContext.parseSessionTime(requested);
        } catch (Exception e) {
            error("无效的完整 session ID: " + requested);
            return;
        }
        if (requested.equals(sessionId())) {
            println("session 已经处于活动状态: " + requested);
            return;
        }

        Path sessionsRoot = workspace.resolve(".code-agent").resolve("sessions").toAbsolutePath().normalize();
        Path targetDir = sessionsRoot.resolve(requested).normalize();
        if (!sessionsRoot.equals(targetDir.getParent())) {
            error("session 不属于当前项目: " + requested);
            return;
        }

        SessionWriter candidateWriter = null;
        try {
            List<com.study.conversation.Message> messages = SessionLoader.loadForResume(targetDir);
            candidateWriter = SessionWriter.openForResume(targetDir, client == null ? "" : client.model());
            Files.createDirectories(targetDir.resolve("tool-results"));
            SessionContext targetContext = new SessionContext(requested, targetDir, targetDir.resolve("tool-results"));
            CompactRuntime targetRuntime = new CompactRuntime(targetContext);
            if (selectedProvider >= 0 && selectedProvider < providers.size()) {
                targetRuntime.setContextWindow(providers.get(selectedProvider).effectiveContextWindow());
            }
            ConversationManager targetConversation = new ConversationManager(
                    this::appendSession, this::replaceSession, this::truncateSession);
            targetConversation.copyFrom(messages);

            SessionWriter oldWriter = sessionWriter;
            sessionContext = targetContext;
            compactRuntime = targetRuntime;
            conversation = targetConversation;
            sessionWriter = candidateWriter;
            candidateWriter = null;
            if (memoryManager != null) {
                memoryManager.resetSummarizedThrough(messages.size());
            }
            closeWriterQuietly(oldWriter);
            println("--- 已恢复 session " + requested + " ---");
            renderHistory(messages);
            println("已恢复，共 " + messages.size() + " 条消息。");
        } catch (Exception e) {
            error("恢复会话失败: " + e.getMessage());
        } finally {
            closeWriterQuietly(candidateWriter);
        }
    }

    private void listSessions() {
        try {
            List<SessionInfo> items = SessionList.list(
                            workspace.resolve(".code-agent").resolve("sessions"))
                    .stream()
                    .filter(this::isResumable)
                    .toList();
            if (items.isEmpty()) {
                println("当前项目没有可恢复的 session。");
                return;
            }
            StringBuilder out = new StringBuilder("当前项目的 session:");
            String current = sessionId();
            for (SessionInfo item : items) {
                out.append(System.lineSeparator())
                        .append(item.id().equals(current) ? "* " : "  ")
                        .append(item.id()).append("  ")
                        .append(item.title()).append("  ")
                        .append(item.model()).append("  ")
                        .append(SESSION_TIME.format(item.modifiedAt()));
            }
            println(out.toString());
        } catch (Exception e) {
            error("读取会话列表失败: " + e.getMessage());
        }
    }

    private boolean isResumable(SessionInfo info) {
        try {
            SessionLoader.loadForResume(info.dir());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void renderHistory(List<com.study.conversation.Message> messages) {
        for (com.study.conversation.Message message : messages) {
            if ("user".equals(message.role())) {
                println("user: " + message.content());
            } else if ("assistant".equals(message.role()) && message.toolCalls().isEmpty()) {
                println("assistant: " + message.content());
            } else if ("assistant".equals(message.role())) {
                println("tool: 调用 " + message.toolCalls().size() + " 个工具");
            } else if ("tool".equals(message.role())) {
                println("tool: 返回 " + message.toolResults().size() + " 条结果");
            }
        }
    }

    @Override
    public void clearAndNewSession() {
        closeSessionWriter();
        sessionContext = null;
        compactRuntime = null;
        conversation = new ConversationManager(
                this::appendSession, this::replaceSession, this::truncateSession);
        usageIn = 0;
        usageOut = 0;
        cacheWrite = 0;
        cacheRead = 0;
        activeSkills.clear();
        pendingInlineSkill = null;
        if (memoryManager != null) {
            memoryManager.resetSummarizedThrough(0);
        }
        println("已清空当前会话；新 session 将在首次请求时创建。");
    }

    @Override
    public List<SkillSummary> listCatalogSkills() {
        return skillSummaries();
    }

    @Override
    public List<String> listActiveSkills() {
        return activeSkills.names();
    }

    @Override
    public void clearActiveSkills() {
        activeSkills.clear();
    }

    @Override
    public void appendAssistantMessage(String text) {
        String body = text == null ? "" : text;
        conversation.addAssistantMessage(body);
        if (currentIo != null) {
            currentIo.println("assistant: " + body);
        }
    }

    @Override
    public List<com.study.conversation.Message> recentMessages(int count) {
        List<com.study.conversation.Message> messages = conversation.getMessages();
        return messages.subList(Math.max(0, messages.size() - Math.max(0, count)), messages.size());
    }

    @Override
    public boolean idle() {
        return !streaming;
    }

    @Override
    public List<ProviderSummary> providers() {
        List<ProviderSummary> summaries = new ArrayList<>();
        for (int i = 0; i < providers.size(); i++) {
            ProviderConfig provider = providers.get(i);
            summaries.add(new ProviderSummary(i + 1, provider.getName(), provider.getModel(), i == selectedProvider));
        }
        return List.copyOf(summaries);
    }

    @Override
    public synchronized ModelSwitchResult switchProvider(String selector) {
        if (!idle()) {
            return new ModelSwitchResult(false, "当前任务运行中，不能切换 provider");
        }
        String normalized = selector == null ? "" : selector.strip();
        if (normalized.isBlank()) {
            return new ModelSwitchResult(false, "缺少 provider 编号或名称");
        }
        List<Integer> matches = new ArrayList<>();
        try {
            int index = Integer.parseInt(normalized) - 1;
            if (index >= 0 && index < providers.size()) {
                matches.add(index);
            }
        } catch (NumberFormatException ignored) {
            for (int i = 0; i < providers.size(); i++) {
                ProviderConfig provider = providers.get(i);
                if (normalized.equalsIgnoreCase(provider.getName())
                        || normalized.equalsIgnoreCase(provider.getModel())) {
                    matches.add(i);
                }
            }
        }
        if (matches.isEmpty()) {
            return new ModelSwitchResult(false, "未找到 provider: " + normalized);
        }
        if (matches.size() > 1) {
            return new ModelSwitchResult(false, "provider 选择有歧义: " + normalized);
        }
        int nextIndex = matches.getFirst();
        if (nextIndex == selectedProvider && client != null) {
            return new ModelSwitchResult(true, "当前已是 " + client.name() + " (" + client.model() + ")");
        }
        ProviderConfig next = providers.get(nextIndex);
        try {
            LlmClient nextClient = LlmClient.create(next, systemPrompt);
            selectedProvider = nextIndex;
            client = nextClient;
            if (compactRuntime != null) {
                compactRuntime.setContextWindow(next.effectiveContextWindow());
            }
            if (memoryManager != null) {
                memoryManager.setClient(nextClient);
            }
            return new ModelSwitchResult(true, "已切换到 " + next.getName() + " (" + next.getModel() + ")");
        } catch (Exception e) {
            return new ModelSwitchResult(false, "切换 provider 失败: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    @Override
    public WorktreeAccessor worktreeAccessor() {
        return worktreeManager == null ? null : new CliWorktreeAccessor();
    }

    @Override
    public TeamAccessor teamAccessor() {
        return teamManager == null ? null : new CliTeamAccessor();
    }

    private List<SkillSummary> skillSummaries() {
        if (skillCatalog == null) {
            return List.of();
        }
        return skillCatalog.list().stream()
                .map(skill -> new SkillSummary(skill.meta().name(), skill.meta().description(),
                        skill.source().toString(), skill.meta().mode()))
                .toList();
    }

    private String skillsCatalogText() {
        if (skillCatalog == null) {
            return "";
        }
        return SkillsBlock.renderSkillsCatalog(skillCatalog.toPromptItems().stream()
                .map(item -> new SkillsBlock.SkillCatalogItem(item.name(), item.description()))
                .toList());
    }

    private void runForkSkill(Skill skill, String renderedBody, Ui ui) {
        try {
            if (!ensureSessionStarted()) {
                return;
            }
            ConversationManager forkConversation = new ConversationManager();
            if ("recent".equals(skill.meta().forkContext()) || "full".equals(skill.meta().forkContext())) {
                forkConversation.copyFrom(recentMessages(5));
            }
            forkConversation.addUserMessage(renderedBody);
            CancelToken forkCancel = new CancelToken();
            Agent forkAgent = new Agent(client, toolRegistry, "0.1.0", permissions, compactRuntime,
                    systemPrompt, null, activeSkills, skill.meta().allowedTools(), hookEngine, hookRuntime,
                    0, false, false, "", effectiveCwd(), sessionId());
            forkAgent.preloadSkillTools(skill);
            BlockingQueue<AgentEvent> queue = forkAgent.run(forkConversation, Mode.DEFAULT, forkCancel);
            AgentEvent.Finished terminal;
            while (true) {
                AgentEvent event = queue.take();
                if (event instanceof AgentEvent.UsageReport usage) {
                    usageIn += usage.inputTokens();
                    usageOut += usage.outputTokens();
                    cacheWrite += usage.cacheWrite();
                    cacheRead += usage.cacheRead();
                } else if (event instanceof AgentEvent.Finished finished) {
                    terminal = finished;
                    break;
                }
            }
            if (terminal.status() != TurnStatus.SUCCEEDED) {
                appendAssistantMessage("[skill " + skill.meta().name() + " failed: " + terminal.reason() + "]");
                return;
            }
            String finalText = forkConversation.getMessages().stream()
                    .filter(message -> "assistant".equals(message.role()) && message.toolCalls().isEmpty())
                    .reduce((first, second) -> second)
                    .map(com.study.conversation.Message::content)
                    .orElse("[skill " + skill.meta().name() + " completed without assistant text]");
            appendAssistantMessage(finalText);
        } catch (Exception e) {
            appendAssistantMessage("[skill " + skill.meta().name() + " failed: " + e.getMessage() + "]");
        }
    }

    private void subscribeTaskNotifications() {
        if (taskManager == null) {
            return;
        }
        taskManager.subscribeDone().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String taskId) {
                taskManager.get(taskId).map(CodeAgentModel.this::taskNotification)
                        .ifPresent(text -> hookRuntime.addReminders(List.of(text)));
            }

            @Override
            public void onError(Throwable throwable) {
                System.err.println("task notification failed: " + throwable.getMessage());
            }

            @Override
            public void onComplete() {
            }
        });
    }

    private void subscribeLeadMail() {
        if (teamManager == null) {
            return;
        }
        Thread.ofVirtual().name("lead-mail-watcher").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    var messages = teamManager.pollLeadMessages();
                    if (!messages.isEmpty()) {
                        StringBuilder out = new StringBuilder("<team-update>").append(System.lineSeparator());
                        for (var message : messages) {
                            out.append("from=").append(message.from())
                                    .append(" type=").append(message.type().name().toLowerCase())
                                    .append(" summary=").append(message.summary()).append(System.lineSeparator())
                                    .append(message.content()).append(System.lineSeparator());
                        }
                        hookRuntime.addReminders(List.of(out.append("</team-update>").toString()));
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    System.err.println("lead mail watcher failed: " + e.getMessage());
                }
            }
        });
    }

    private String taskNotification(BackgroundTask task) {
        StringBuilder out = new StringBuilder("<task-notification>").append(System.lineSeparator());
        out.append("Task ").append(task.id());
        if (!task.name().isBlank()) {
            out.append(" (name=\"").append(task.name()).append("\")");
        }
        out.append(": ").append(task.status().name().toLowerCase()).append(System.lineSeparator());
        if (!task.result().isBlank()) {
            out.append("Result: ").append(task.result()).append(System.lineSeparator());
        }
        if (!task.error().isBlank()) {
            out.append("Error: ").append(task.error()).append(System.lineSeparator());
        }
        return out.append("</task-notification>").toString();
    }

    private Path effectiveCwd() {
        return activeCwd == null ? workspace : activeCwd;
    }

    private Path activeCwdFromSession(WorktreeManager manager) {
        if (manager == null || manager.currentSession() == null) {
            return null;
        }
        return Path.of(manager.currentSession().worktreePath()).toAbsolutePath().normalize();
    }

    private void finishTurn() {
        streaming = false;
        iter = 0;
        turnCancel = null;
        agentQueue = null;
    }

    @Override
    public void close() {
        cancelActiveTurn();
        closeSessionWriter();
    }

    private final class CliWorktreeAccessor implements WorktreeAccessor {
        @Override
        public CreateResult create(String name) throws java.io.IOException {
            Worktree wt = worktreeManager.create(name, "HEAD", true);
            return new CreateResult(wt.path().toString(), wt.branch());
        }

        @Override
        public List<WorktreeSummary> list() {
            WorktreeSession session = worktreeManager.currentSession();
            String activeName = session == null ? "" : session.worktreeName();
            return worktreeManager.list().stream()
                    .map(wt -> new WorktreeSummary(wt.name(), wt.path().toString(), wt.branch(),
                            wt.name().equals(activeName), wt.manual()))
                    .toList();
        }

        @Override
        public EnterResult enter(String name) throws java.io.IOException {
            WorktreeSession session = worktreeManager.enter(name);
            activeCwd = Path.of(session.worktreePath()).toAbsolutePath().normalize();
            return new EnterResult(session.worktreeName(), session.worktreePath());
        }

        @Override
        public ExitResult exit(boolean remove, boolean discard) throws java.io.IOException {
            WorktreeSession session = worktreeManager.currentSession();
            if (session == null) {
                throw new java.io.IOException("当前没有 active worktree");
            }
            var report = worktreeManager.exit(session.worktreeName(),
                    remove ? ExitAction.REMOVE : ExitAction.KEEP, new ExitOptions(discard));
            activeCwd = null;
            return new ExitResult(report.removed(), report.path(), report.branch());
        }

        @Override
        public void remove(String name, boolean discard) throws java.io.IOException {
            worktreeManager.remove(name, new ExitOptions(discard));
            WorktreeSession session = worktreeManager.currentSession();
            if (session != null && session.worktreeName().equals(name)) {
                activeCwd = null;
            }
        }
    }

    private final class CliTeamAccessor implements TeamAccessor {
        @Override
        public List<TeamSummary> list() {
            return teamManager.list().stream()
                    .map(team -> {
                        int active = (int) team.members().stream()
                                .filter(member -> member.isActive() == null || Boolean.TRUE.equals(member.isActive()))
                                .count();
                        return new TeamSummary(team.sanitizedName(), team.backend().wireValue(),
                                team.members().size(), active);
                    }).toList();
        }

        @Override
        public String info(String name) throws java.io.IOException {
            Team team = teamManager.get(name)
                    .orElseThrow(() -> new java.io.IOException("Team 不存在: " + name));
            StringBuilder out = new StringBuilder();
            out.append("Team: ").append(team.sanitizedName()).append(System.lineSeparator());
            out.append("Backend: ").append(team.backend().wireValue()).append(System.lineSeparator());
            out.append("Config: ").append(team.configPath()).append(System.lineSeparator());
            out.append("Members:");
            for (TeammateInfo member : team.members()) {
                out.append(System.lineSeparator()).append("- ").append(member.name())
                        .append(" id=").append(member.agentId())
                        .append(" active=").append(member.isActive())
                        .append(" worktree=").append(member.worktreePath());
            }
            return out.toString();
        }

        @Override
        public void delete(String name, boolean force) throws java.io.IOException {
            teamManager.delete(name, force);
        }
    }
}
