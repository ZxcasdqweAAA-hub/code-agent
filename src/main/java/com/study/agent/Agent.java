package com.study.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.study.compact.CompactResult;
import com.study.compact.CompactRuntime;
import com.study.compact.AutoContextCompactor;
import com.study.compact.ToolResultOffloader;
import com.study.conversation.ConversationManager;
import com.study.llm.LlmSystem;
import com.study.llm.LlmClient;
import com.study.llm.LlmStream;
import com.study.llm.Request;
import com.study.llm.StreamEvent;
import com.study.llm.ToolCall;
import com.study.llm.ToolResult;
import com.study.memory.MemoryManager;
import com.study.hook.DispatchResult;
import com.study.hook.Event;
import com.study.hook.HookEngine;
import com.study.hook.HookRuntime;
import com.study.hook.Payload;
import com.study.permission.Decision;
import com.study.permission.Mode;
import com.study.permission.Outcome;
import com.study.permission.PermissionEngine;
import com.study.permission.PermissionResult;
import com.study.prompt.Environment;
import com.study.prompt.PromptBuilder;
import com.study.prompt.Reminder;
import com.study.prompt.SkillsBlock;
import com.study.skills.ActiveSkills;
import com.study.skills.Render;
import com.study.skills.Skill;
import com.study.skills.ToolSpec;
import com.study.tool.LoadSkillTool;
import com.study.tool.SkillTool;
import com.study.tool.Tool;
import com.study.tool.ToolContext;
import com.study.tool.ToolExecutionResult;
import com.study.tool.ToolRegistry;
import com.study.tool.Truncate;
import com.study.team.TeamContext;
import com.study.team.mailbox.Message;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Agent {
    public static final int MAX_ITERATIONS = 25;
    public static final int MAX_UNKNOWN_RUN = 3;
    private static final int PLAN_REMINDER_INTERVAL = 4;
    private static final long STREAM_IDLE_TIMEOUT_SECONDS = 300;
    private static final String TOOL_SEARCH_NAME = "ToolSearch";
    private static final String LOAD_SKILL_NAME = "load_skill";
    public static final String NOTICE_MAX_ITER = "(已达最大迭代轮数 25，自动停止；可继续发消息推进。)";
    public static final String NOTICE_UNKNOWN_TOOLS = "(连续多轮只请求到未注册的工具，自动停止。)";
    public static final String NOTICE_STREAM_ERR = "(请求出错，本轮已中断。)";
    public static final String NOTICE_CANCELLED = "(已取消。)";
    public static final String NOTICE_FAILED_TEMPLATE = "(本轮任务已中止：%s；可重新发送消息重试。)";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmClient client;
    private final ToolRegistry registry;
    private final ToolRegistry skillToolRegistry = new ToolRegistry();
    private final String version;
    private final PermissionEngine permissions;
    private final CompactRuntime compactRuntime;
    private final String systemPrompt;
    private final MemoryManager memoryManager;
    private final ActiveSkills activeSkills;
    private final List<String> allowedTools;
    private final HookEngine hookEngine;
    private final HookRuntime hookRuntime;
    private final int maxTurns;
    private final boolean dontAsk;
    private final boolean subAgent;
    private final String subAgentName;
    private final Map<String, ToolResult> pendingMemoryToolResultReplacements = new LinkedHashMap<>();
    private final Path workspace;
    private final String sessionId;
    private volatile TeamContext teamContext;
    private volatile ApprovalHandler approvalHandler;
    private volatile Mode runMode = Mode.DEFAULT;

    public Agent(LlmClient client, ToolRegistry registry) {
        this(client, registry, "dev");
    }

    public Agent(LlmClient client, ToolRegistry registry, String version) {
        this(client, registry, version, PermissionEngine.create(java.nio.file.Path.of("")));
    }

    public Agent(LlmClient client, ToolRegistry registry, String version, PermissionEngine permissions) {
        this(client, registry, version, permissions, null);
    }

    public Agent(LlmClient client, ToolRegistry registry, String version, PermissionEngine permissions, CompactRuntime compactRuntime) {
        this(client, registry, version, permissions, compactRuntime, "");
    }

    public Agent(LlmClient client, ToolRegistry registry, String version, PermissionEngine permissions,
                 CompactRuntime compactRuntime, String systemPrompt) {
        this(client, registry, version, permissions, compactRuntime, systemPrompt, null);
    }

    public Agent(LlmClient client, ToolRegistry registry, String version, PermissionEngine permissions,
                 CompactRuntime compactRuntime, String systemPrompt, MemoryManager memoryManager) {
        this(client, registry, version, permissions, compactRuntime, systemPrompt, memoryManager, null, List.of());
    }

    public Agent(LlmClient client, ToolRegistry registry, String version, PermissionEngine permissions,
                 CompactRuntime compactRuntime, String systemPrompt, MemoryManager memoryManager,
                 ActiveSkills activeSkills, List<String> allowedTools) {
        this(client, registry, version, permissions, compactRuntime, systemPrompt, memoryManager,
                activeSkills, allowedTools, HookEngine.empty(), new HookRuntime());
    }

    public Agent(LlmClient client, ToolRegistry registry, String version, PermissionEngine permissions,
                 CompactRuntime compactRuntime, String systemPrompt, MemoryManager memoryManager,
                 ActiveSkills activeSkills, List<String> allowedTools, HookEngine hookEngine,
                 HookRuntime hookRuntime) {
        this(client, registry, version, permissions, compactRuntime, systemPrompt, memoryManager,
                activeSkills, allowedTools, hookEngine, hookRuntime, 0, false, false);
    }

    public Agent(LlmClient client, ToolRegistry registry, String version, PermissionEngine permissions,
                 CompactRuntime compactRuntime, String systemPrompt, MemoryManager memoryManager,
                 ActiveSkills activeSkills, List<String> allowedTools, HookEngine hookEngine,
                 HookRuntime hookRuntime, int maxTurns, boolean dontAsk, boolean subAgent) {
        this(client, registry, version, permissions, compactRuntime, systemPrompt, memoryManager,
                activeSkills, allowedTools, hookEngine, hookRuntime, maxTurns, dontAsk, subAgent, "");
    }

    public Agent(LlmClient client, ToolRegistry registry, String version, PermissionEngine permissions,
                 CompactRuntime compactRuntime, String systemPrompt, MemoryManager memoryManager,
                 ActiveSkills activeSkills, List<String> allowedTools, HookEngine hookEngine,
                 HookRuntime hookRuntime, int maxTurns, boolean dontAsk, boolean subAgent, String subAgentName) {
        this(client, registry, version, permissions, compactRuntime, systemPrompt, memoryManager,
                activeSkills, allowedTools, hookEngine, hookRuntime, maxTurns, dontAsk, subAgent, subAgentName,
                null, "");
    }

    public Agent(LlmClient client, ToolRegistry registry, String version, PermissionEngine permissions,
                 CompactRuntime compactRuntime, String systemPrompt, MemoryManager memoryManager,
                 ActiveSkills activeSkills, List<String> allowedTools, HookEngine hookEngine,
                 HookRuntime hookRuntime, int maxTurns, boolean dontAsk, boolean subAgent, String subAgentName,
                 Path workspace, String sessionId) {
        this.client = client;
        this.registry = registry;
        this.version = version == null || version.isBlank() ? "dev" : version;
        this.permissions = permissions == null ? PermissionEngine.create(java.nio.file.Path.of("")) : permissions;
        this.compactRuntime = compactRuntime;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.memoryManager = memoryManager;
        this.activeSkills = activeSkills;
        this.allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        this.hookEngine = hookEngine == null ? HookEngine.empty() : hookEngine;
        this.hookRuntime = hookRuntime == null ? new HookRuntime() : hookRuntime;
        this.maxTurns = Math.max(0, maxTurns);
        this.dontAsk = dontAsk;
        this.subAgent = subAgent;
        this.subAgentName = subAgentName == null ? "" : subAgentName;
        this.workspace = workspace == null ? workspaceFromCompact(compactRuntime) : workspace.toAbsolutePath().normalize();
        this.sessionId = sessionId == null || sessionId.isBlank() ? sessionIdFromCompact(compactRuntime) : sessionId;
    }

    public void setTeamContext(TeamContext teamContext) {
        this.teamContext = teamContext;
    }

    public void setApprovalHandler(ApprovalHandler approvalHandler) {
        this.approvalHandler = approvalHandler;
    }

    public void setRunMode(Mode runMode) {
        this.runMode = runMode == null ? Mode.DEFAULT : runMode;
    }

    public BlockingQueue<AgentEvent> run(ConversationManager conversation) {
        return run(conversation, Mode.DEFAULT, new CancelToken());
    }

    public BlockingQueue<AgentEvent> run(ConversationManager conversation, Mode mode, CancelToken cancel) {
        return run(conversation, mode, cancel, TurnCheckpoint.capture(conversation));
    }

    public BlockingQueue<AgentEvent> run(ConversationManager conversation, Mode mode, CancelToken cancel,
                                         TurnCheckpoint checkpoint) {
        BlockingQueue<AgentEvent> out = new LinkedBlockingQueue<>();
        TurnCheckpoint safeCheckpoint = checkpoint == null ? TurnCheckpoint.capture(conversation) : checkpoint;
        Thread.ofVirtual().name("agent-loop").start(() ->
                runLoop(conversation, mode, cancel, out, safeCheckpoint, new AtomicBoolean()));
        return out;
    }

    public String runToCompletion(ConversationManager conversation, String task, CancelToken cancel)
            throws InterruptedException {
        return runToCompletion(conversation, task, cancel, runMode);
    }

    public String runToCompletion(ConversationManager conversation, String task, CancelToken cancel, Mode mode)
            throws InterruptedException {
        TurnCheckpoint checkpoint = TurnCheckpoint.capture(conversation);
        if (task != null && !task.isBlank()) {
            conversation.addUserMessage(task);
        }
        BlockingQueue<AgentEvent> events = run(conversation, mode == null ? Mode.DEFAULT : mode,
                cancel == null ? new CancelToken() : cancel, checkpoint);
        AgentEvent.Finished terminal = null;
        while (true) {
            AgentEvent event = events.take();
            if (event instanceof AgentEvent.Approval approval) {
                approval.request().respond().offer(dontAsk ? Outcome.ALLOW_ONCE : Outcome.DENY_ONCE);
            } else if (event instanceof AgentEvent.Finished finished) {
                terminal = finished;
                break;
            }
        }
        if (terminal == null) {
            throw new IllegalStateException("Agent 未产生终止状态");
        }
        if (terminal.status() != TurnStatus.SUCCEEDED) {
            throw new IllegalStateException(terminal.reason());
        }
        return lastAssistantText(conversation);
    }

    private void runLoop(ConversationManager conversation, Mode mode, CancelToken cancel, BlockingQueue<AgentEvent> out,
                         TurnCheckpoint checkpoint, AtomicBoolean terminalEmitted) {
        int unknownRun = 0;
        boolean toolExecutedThisTurn = false;
        pendingMemoryToolResultReplacements.clear();
        try {
            List<Map<String, Object>> definitions = buildToolDefinitions(mode);
            String stableSystem = systemPrompt.isBlank() ? PromptBuilder.buildSystemPrompt() : systemPrompt;

            int turns = maxTurns > 0 ? maxTurns : MAX_ITERATIONS;
            for (int iter = 1; iter <= turns; iter++) {
                String environment = buildEnvironment();
                if (!emit(cancel, out, new AgentEvent.Iter(iter))) {
                    finishCancelled(conversation, checkpoint, toolExecutedThisTurn, out, terminalEmitted);
                    return;
                }

                String reminder = combineReminders(
                        combineReminders(combineReminders(reminderFor(mode, iter), hookReminder()), teamIncomingReminder()),
                        registry.deferredToolsReminder());
                if (compactRuntime != null) {
                    try {
                        CompactResult compact = AutoContextCompactor.manageAuto(
                                conversation,
                                client,
                                compactRuntime,
                                new LlmSystem(stableSystem, environment),
                                reminder,
                                definitions);
                        if (compact.summarized()) {
                            out.add(new AgentEvent.Compact(compact.beforeTokens(), compact.afterTokens(), compact.message()));
                        }
                    } catch (Exception e) {
                        if (cancel.isCancelled()) {
                            finishCancelled(conversation, checkpoint, toolExecutedThisTurn, out, terminalEmitted);
                        } else {
                            failContextTurn(conversation, checkpoint, toolExecutedThisTurn,
                                    "自动压缩上下文失败: " + e.getMessage(), out, terminalEmitted);
                        }
                        return;
                    }
                }
                StreamCapture capture = streamOnce(conversation, definitions, stableSystem, environment, reminder, out, cancel);
                if (capture.error != null) {
                    if (compactRuntime != null && AutoContextCompactor.isPromptTooLong(capture.error)) {
                        CompactResult compact;
                        try {
                            compact = AutoContextCompactor.compactEmergency(
                                    conversation,
                                    client,
                                    compactRuntime,
                                    new LlmSystem(stableSystem, environment),
                                    reminder,
                                    definitions);
                        } catch (Exception e) {
                            failContextTurn(conversation, checkpoint, toolExecutedThisTurn,
                                    "紧急压缩上下文失败: " + e.getMessage(), out, terminalEmitted);
                            return;
                        }
                        out.add(new AgentEvent.Compact(compact.beforeTokens(), compact.afterTokens(),
                                "上下文过长，已自动压缩并重试，token 从 " + compact.beforeTokens() + " 降至 " + compact.afterTokens()));
                        capture = streamOnce(conversation, definitions, stableSystem, environment, reminder, out, cancel);
                        if (capture.error == null) {
                            // Continue normal handling below.
                        } else {
                            failContextTurn(conversation, checkpoint, toolExecutedThisTurn,
                                    "紧急压缩后重试失败: " + capture.error, out, terminalEmitted);
                            return;
                        }
                    }
                }
                if (capture.error != null) {
                    if (cancel.isCancelled() || capture.cancelled) {
                        finishCancelled(conversation, checkpoint, toolExecutedThisTurn, out, terminalEmitted);
                    } else {
                        failContextTurn(conversation, checkpoint, toolExecutedThisTurn,
                                capture.error, out, terminalEmitted);
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
                    recordPredictionBaseline(capture, conversation);
                    alignLargeToolResultsInMemory(conversation);
                    if (!subAgent) {
                        triggerMemoryUpdate(conversation);
                    }
                    finish(out, terminalEmitted, TurnStatus.SUCCEEDED, "");
                    return;
                }

                conversation.addAssistantWithToolCalls(capture.text.toString(), capture.toolCalls);
                recordPredictionBaseline(capture, conversation);
                unknownRun = allUnknown(capture.toolCalls) ? unknownRun + 1 : 0;
                BatchOutcome batch = executeBatched(capture.toolCalls, mode, cancel, out, conversation);
                toolExecutedThisTurn |= batch.executedAnyTool();
                recordReadFiles(capture.toolCalls, batch.results());
                appendToolResults(conversation, capture.toolCalls, batch.results());

                if (!batch.completed()) {
                    finishCancelled(conversation, checkpoint, toolExecutedThisTurn, out, terminalEmitted);
                    return;
                }
                if (shouldRefreshToolDefinitions(capture.toolCalls, batch.results())) {
                    definitions = buildToolDefinitions(mode);
                }
                AutoSkillAction autoSkill = autoSkillAction(capture.toolCalls, batch.results());
                if (autoSkill != null) {
                    if (!executeAutoSkill(autoSkill, conversation, mode, cancel, out, terminalEmitted)) {
                        return;
                    }
                    continue;
                }
                if (unknownRun >= MAX_UNKNOWN_RUN) {
                    out.add(new AgentEvent.Notice(NOTICE_UNKNOWN_TOOLS));
                    ensureAssistantTail(conversation, NOTICE_UNKNOWN_TOOLS);
                    alignLargeToolResultsInMemory(conversation);
                    finish(out, terminalEmitted, TurnStatus.FAILED, "");
                    return;
                }
            }

            out.add(new AgentEvent.Notice(NOTICE_MAX_ITER));
            ensureAssistantTail(conversation, NOTICE_MAX_ITER);
            alignLargeToolResultsInMemory(conversation);
            finish(out, terminalEmitted, TurnStatus.FAILED, "");
        } catch (Exception e) {
            failContextTurn(conversation, checkpoint, toolExecutedThisTurn,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), out, terminalEmitted);
        } finally {
            skillToolRegistry.clear();
        }
    }

    private List<Map<String, Object>> buildToolDefinitions(Mode mode) {
        List<Map<String, Object>> globalDefinitions = mode == Mode.PLAN
                ? registry.planDefinitions()
                : registry.getAllSchemas("openai");
        List<Map<String, Object>> skillDefinitions = mode == Mode.PLAN
                ? skillToolRegistry.planDefinitions()
                : skillToolRegistry.getAllSchemas("openai");
        List<Map<String, Object>> definitions = new ArrayList<>(globalDefinitions);
        if (!allowedTools.isEmpty()) {
            definitions = new ArrayList<>(registry.definitionsFiltered(allowedTools, "openai"));
            definitions.addAll(skillToolRegistry.definitionsFiltered(allowedTools, "openai"));
            if (subAgent) {
                definitions = definitions.stream()
                        .filter(def -> isAllowedTool(String.valueOf(def.get("name"))))
                        .toList();
            }
        } else {
            definitions.addAll(skillDefinitions);
        }
        return definitions;
    }

    private boolean shouldRefreshToolDefinitions(List<ToolCall> calls, List<ToolResult> results) {
        int size = Math.min(calls.size(), results.size());
        for (int i = 0; i < size; i++) {
            String name = calls.get(i).name();
            if ((TOOL_SEARCH_NAME.equals(name) || LOAD_SKILL_NAME.equals(name))
                    && !results.get(i).error()) {
                return true;
            }
        }
        return false;
    }

    private String buildEnvironment() {

        return Environment.gather(version, client.model()).render();
    }

    private AutoSkillAction autoSkillAction(List<ToolCall> calls, List<ToolResult> results) {
        if (calls == null || results == null) {
            return null;
        }
        Tool tool = registry.get("load_skill").orElse(null);
        if (!(tool instanceof LoadSkillTool loadSkillTool)) {
            return null;
        }
        for (int i = 0; i < Math.min(calls.size(), results.size()); i++) {
            ToolCall call = calls.get(i);
            ToolResult result = results.get(i);
            if (!"load_skill".equals(call.name()) || result.error()) {
                continue;
            }
            String name = skillNameFromArguments(call.arguments());
            if (name.isBlank()) {
                continue;
            }
            Skill skill = loadSkillTool.skill(name).orElse(null);
            if (skill == null) {
                continue;
            }
            if (activeSkills != null) {
                activeSkills.deactivate(skill.meta().name());
            }
            return new AutoSkillAction(skill, Render.renderBody(skill, ""));
        }
        return null;
    }

    private boolean executeAutoSkill(AutoSkillAction action, ConversationManager conversation, Mode mode,
                                     CancelToken cancel, BlockingQueue<AgentEvent> out,
                                     AtomicBoolean terminalEmitted) throws InterruptedException {
        Skill skill = action.skill();
        if (!skill.meta().isFork()) {
            conversation.addUserMessage(action.renderedBody());
            out.add(new AgentEvent.Notice("Skill " + skill.meta().name() + " selected; executing inline."));
            return true;
        }
        String finalText = runForkSkill(action, conversation, cancel, out);
        conversation.addAssistantMessage(finalText);
        out.add(new AgentEvent.Text(finalText));
        alignLargeToolResultsInMemory(conversation);
        triggerMemoryUpdate(conversation);
        finish(out, terminalEmitted, TurnStatus.SUCCEEDED, "");
        return false;
    }

    private String runForkSkill(AutoSkillAction action, ConversationManager conversation,
                                CancelToken cancel, BlockingQueue<AgentEvent> out) throws InterruptedException {
        Skill skill = action.skill();
        try {
            ConversationManager forkConversation = new ConversationManager();
            if ("recent".equals(skill.meta().forkContext()) || "full".equals(skill.meta().forkContext())) {
                List<com.study.conversation.Message> messages = conversation.getMessages();
                int from = Math.max(0, messages.size() - 5);
                forkConversation.copyFrom(messages.subList(from, messages.size()));
                if ("full".equals(skill.meta().forkContext())) {
                    System.err.println("skill " + skill.meta().name() + " fork_context=full is treated as recent");
                }
            }
            forkConversation.addUserMessage(action.renderedBody());
            Agent forkAgent = new Agent(client, registry, version, permissions, compactRuntime,
                    systemPrompt, null, null, skill.meta().allowedTools(), hookEngine, hookRuntime);
            forkAgent.preloadSkillTools(skill);
            BlockingQueue<AgentEvent> forkEvents = forkAgent.run(forkConversation, Mode.DEFAULT, cancel);
            AgentEvent.Finished terminal = null;
            while (true) {
                AgentEvent event = forkEvents.take();
                if (event instanceof AgentEvent.UsageReport usage) {
                    out.add(usage);
                } else if (event instanceof AgentEvent.Finished finished) {
                    terminal = finished;
                    break;
                }
            }
            if (terminal == null || terminal.status() != TurnStatus.SUCCEEDED) {
                String reason = terminal == null ? "missing terminal event" : terminal.reason();
                return "[skill " + skill.meta().name() + " failed: " + reason + "]";
            }
            return forkConversation.getMessages().stream()
                    .filter(message -> "assistant".equals(message.role()) && message.toolCalls().isEmpty())
                    .reduce((first, second) -> second)
                    .map(com.study.conversation.Message::content)
                    .orElse("[skill " + skill.meta().name() + " completed without assistant text]");
        } catch (Exception e) {
            return "[skill " + skill.meta().name() + " failed: " + e.getMessage() + "]";
        }
    }

    private String skillNameFromArguments(String arguments) {
        try {
            JsonNode root = JSON.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
            return root.path("name").asText("").strip();
        } catch (Exception e) {
            return "";
        }
    }

    private StreamCapture streamOnce(ConversationManager conversation, List<Map<String, Object>> tools,
                                     String stableSystem, String environment, String reminder,
                                     BlockingQueue<AgentEvent> out, CancelToken cancel) throws InterruptedException {
        StreamCapture capture = new StreamCapture();
        Request request = new Request(conversation.getMessages(), tools, new LlmSystem(stableSystem, environment), reminder);
        try (LlmStream llmStream = client.stream(request)) {
            AutoCloseable cancelRegistration = cancel.onCancel(llmStream::cancel);
            BlockingQueue<StreamEvent> stream = llmStream.events();
            try {
                while (true) {
                    StreamEvent event = stream.poll(STREAM_IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (event == null) {
                        llmStream.cancel();
                        capture.error = "Subagent execution failed";
                        return capture;
                    }
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
                        case StreamEvent.Cancelled cancelled -> {
                            capture.cancelled = true;
                            capture.error = cancelled.message();
                            return capture;
                        }
                        default -> {
                        }
                    }
                }
            } finally {
                closeQuietly(cancelRegistration);
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

    private String hookReminder() {
        List<String> reminders = hookRuntime.drainReminders();
        if (reminders.isEmpty()) {
            return "";
        }
        return String.join("\n\n", reminders);
    }

    private String combineReminders(String first, String second) {
        if (first == null || first.isBlank()) {
            return second == null ? "" : second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + "\n\n" + second;
    }

    private BatchOutcome executeBatched(List<ToolCall> calls, Mode mode, CancelToken cancel, BlockingQueue<AgentEvent> out,
                                        ConversationManager conversation)
            throws InterruptedException {
        ToolResult[] results = new ToolResult[calls.size()];
        boolean[] executed = new boolean[calls.size()];
        int i = 0;
        while (i < calls.size()) {
            if (cancel.isCancelled()) {
                fillCancelled(calls, results, i);
                return new BatchOutcome(Arrays.stream(results).filter(Objects::nonNull).toList(), false,
                        anyTrue(executed));
            }
            if (registry.isReadOnly(calls.get(i).name())) {
                int start = i;
                int end = i + 1;
                while (end < calls.size() && registry.isReadOnly(calls.get(end).name())) {
                    end++;
                }
                if (requiresSerialApproval(calls, start, end, mode)) {
                    for (int index = start; index < end; index++) {
                        ExecutionOutcome outcome = executeOne(calls.get(index), mode, cancel, out, conversation);
                        results[index] = outcome.result();
                        executed[index] = outcome.executed();
                    }
                } else {
                    executeReadOnlyBatch(calls, results, executed, start, end, mode, cancel, out, conversation);
                }
                i = end;
            } else {
                ExecutionOutcome outcome = executeOne(calls.get(i), mode, cancel, out, conversation);
                results[i] = outcome.result();
                executed[i] = outcome.executed();
                i++;
            }
        }
        return new BatchOutcome(List.of(results), true, anyTrue(executed));
    }

    private boolean requiresSerialApproval(List<ToolCall> calls, int start, int end, Mode mode) {
        for (int i = start; i < end; i++) {
            ToolCall call = calls.get(i);
            Tool tool = skillToolRegistry.get(call.name())
                    .or(() -> registry.get(call.name()))
                    .orElse(null);
            if (permissions.check(mode, call, tool, workspace).decision() == Decision.ASK) {
                return true;
            }
        }
        return false;
    }

    private void executeReadOnlyBatch(List<ToolCall> calls, ToolResult[] results, boolean[] executed, int start, int end,
                                      Mode mode, CancelToken cancel, BlockingQueue<AgentEvent> out,
                                      ConversationManager conversation) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(end - start);
        for (int i = start; i < end; i++) {
            ToolCall call = calls.get(i);
            out.add(new AgentEvent.Tool(new ToolEvent(call.id(), call.name(), preview(call.arguments()), Phase.START, "", false)));
        }
        for (int i = start; i < end; i++) {
            int index = i;
            Thread.ofVirtual().name("tool-" + calls.get(index).name()).start(() -> {
                try {
                    ExecutionOutcome outcome = executeOneNoEvents(calls.get(index), mode, cancel, out, conversation);
                    results[index] = outcome.result();
                    executed[index] = outcome.executed();
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

    private ExecutionOutcome executeOne(ToolCall call, Mode mode, CancelToken cancel, BlockingQueue<AgentEvent> out,
                                  ConversationManager conversation) {
        out.add(new AgentEvent.Tool(new ToolEvent(call.id(), call.name(), preview(call.arguments()), Phase.START, "", false)));
        ExecutionOutcome outcome = executeOneNoEvents(call, mode, cancel, out, conversation);
        ToolResult result = outcome.result();
        out.add(new AgentEvent.Tool(new ToolEvent(call.id(), call.name(), preview(call.arguments()), Phase.END,
                summarize(result.content()), result.error())));
        return outcome;
    }

    private ExecutionOutcome executeOneNoEvents(ToolCall call, Mode mode, CancelToken cancel, BlockingQueue<AgentEvent> out,
                                           ConversationManager conversation) {
        if (cancel.isCancelled()) {
            return notExecuted(new ToolResult(call.id(), NOTICE_CANCELLED, true));
        }
        if (!allowedTools.isEmpty() && !isAllowedTool(call.name()) && !isSystemTool(call.name())) {
            return notExecuted(new ToolResult(call.id(), "Tool is not available in this restricted execution: " + call.name(), true));
        }
        DispatchResult pre = hookEngine.dispatch(Event.PRE_TOOL_USE, toolPayload(Event.PRE_TOOL_USE, call, null), hookRuntime);
        hookRuntime.addReminders(pre.injectedPrompts());
        if (pre.blocked()) {
            return notExecuted(new ToolResult(call.id(), "[hook " + pre.blockingHookName() + "] " + pre.reason(), true));
        }
        Tool tool = skillToolRegistry.get(call.name()).orElse(null);
        if (tool == null) {
            tool = registry.get(call.name()).orElse(null);
        }
        if (tool != null && tool.isSystem()) {
            ToolExecutionResult result = executeTool(tool, call, mode, cancel, out, conversation);
            return executed(afterTool(call, result));
        }
        PermissionResult permission = permissions.check(mode, call, tool, workspace);
        if (permission.decision() == Decision.DENY) {
            return notExecuted(new ToolResult(call.id(), "Permission denied: " + permission.reason(), true));
        }
        if (permission.decision() == Decision.ASK) {
            if (dontAsk) {
                ToolExecutionResult result = executeTool(tool, call, mode, cancel, out, conversation);
                return executed(afterTool(call, result));
            }
            if (subAgent && approvalHandler == null) {
                return notExecuted(new ToolResult(call.id(), "Permission denied: subagent approval escalation is not enabled.", true));
            }
            Outcome outcome = requestApproval(call, permission.reason(), cancel, out);
            if (outcome == Outcome.ALLOW_FOREVER && "Bash".equals(call.name())) {
                try {
                    permissions.persistPersonalAllow(call);
                    out.add(new AgentEvent.Notice("已将该 Bash 命令永久允许并写入个人级配置。"));
                } catch (Exception e) {
                    out.add(new AgentEvent.Notice("永久允许写入失败，本次仍继续执行；请检查个人级配置目录是否可写。"));
                }
            } else if (outcome != Outcome.ALLOW_ONCE) {
                return notExecuted(new ToolResult(call.id(), "Permission denied by user: " + permission.reason(), true));
            }
        }
        if (tool == null) {
            ToolExecutionResult result = registry.execute(toolContext(), call.name(), call.arguments());
            return notExecuted(afterTool(call, result));
        }
        ToolExecutionResult result = executeTool(tool, call, mode, cancel, out, conversation);
        return executed(afterTool(call, result));
    }

    private ToolExecutionResult executeTool(Tool tool, ToolCall call, Mode mode, CancelToken cancel, BlockingQueue<AgentEvent> out,
                                            ConversationManager conversation) {
        if (tool instanceof LoadSkillTool loadSkillTool) {
            return loadSkillTool.execute(parseArgs(call.arguments()), skillToolRegistry);
        }
        if (tool instanceof AgentTool agentTool) {
            ApprovalHandler childApproval = (request, childCancel) -> awaitApproval(request, childCancel, out);
            return agentTool.execute(new AgentTool.Context(this, conversation, cancel, subAgent, mode, childApproval), parseArgs(call.arguments()));
        }
        if (skillToolRegistry.get(tool.name()).isPresent()) {
            return tool.execute(
                    toolContext(),
                    parseArgs(call.arguments())
            );
        }
        return registry.execute(toolContext(), call.name(), call.arguments());
    }

    public void preloadSkillTools(Skill skill) {
        if (skill == null) {
            return;
        }
        for (ToolSpec spec : skill.toolSpecs()) {
            skillToolRegistry.registerSkillTool(SkillTool.fromSpec(
                    spec.name(), spec.description(), spec.inputSchemaJson(), spec.command(), spec.baseDir()));
        }
    }

    private ToolResult afterTool(ToolCall call, ToolExecutionResult result) {
        ToolResult toolResult = new ToolResult(call.id(), result.content(), result.error());
        DispatchResult post = hookEngine.dispatch(Event.POST_TOOL_USE, toolPayload(Event.POST_TOOL_USE, call, toolResult), hookRuntime);
        hookRuntime.addReminders(post.injectedPrompts());
        return toolResult;
    }

    private Payload toolPayload(Event event, ToolCall call, ToolResult result) {
        Map<String, Object> args = parseArgs(call.arguments());
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("event", event.wireName());
        payload.put("tool_call_id", call.id());
        payload.put("tool_name", call.name());
        payload.put("agent_role", subAgent ? "subagent" : "main");
        if (subAgent && !subAgentName.isBlank()) {
            payload.put("subagent_name", subAgentName);
        }
        payload.put("tool_input", args);
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            payload.put("tool_input." + entry.getKey(), entry.getValue());
        }
        if (!sessionId.isBlank()) {
            payload.put("session_id", sessionId);
        }
        payload.put("cwd", workspace.toString());
        if (result != null) {
            payload.put("tool_error", result.error());
            payload.put("tool_result", result.content());
        }
        return new Payload(payload);
    }

    private Path workspaceFromCompact(CompactRuntime runtime) {
        if (runtime == null) {
            return Path.of("").toAbsolutePath().normalize();
        }
        Path sessionDir = runtime.session().sessionDir();
        Path sessionsDir = sessionDir == null ? null : sessionDir.getParent();
        Path codeAgentDir = sessionsDir == null ? null : sessionsDir.getParent();
        Path root = codeAgentDir == null ? null : codeAgentDir.getParent();
        return root == null ? Path.of("").toAbsolutePath().normalize() : root;
    }

    private String sessionIdFromCompact(CompactRuntime runtime) {
        return runtime == null || runtime.session() == null ? "" : runtime.session().sessionId();
    }

    private Map<String, Object> parseArgs(String arguments) {
        try {
            return JSON.readValue(arguments == null || arguments.isBlank() ? "{}" : arguments, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String lastAssistantText(ConversationManager conversation) {
        return conversation.getMessages().stream()
                .filter(message -> "assistant".equals(message.role()) && message.toolCalls().isEmpty())
                .reduce((first, second) -> second)
                .map(com.study.conversation.Message::content)
                .orElse("");
    }

    public ToolRegistry registry() {
        return registry;
    }

    public LlmClient client() {
        return client;
    }

    public String version() {
        return version;
    }

    public PermissionEngine permissions() {
        return permissions;
    }

    public CompactRuntime compactRuntime() {
        return compactRuntime;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public HookEngine hookEngine() {
        return hookEngine;
    }

    public HookRuntime hookRuntime() {
        return hookRuntime;
    }

    public Path workspace() {
        return workspace;
    }

    private ToolContext toolContext() {
        ToolContext context = ToolContext.withCwd(workspace);
        return teamContext == null ? context : context.withTeam(teamContext);
    }

    private String teamIncomingReminder() {
        TeamContext context = teamContext;
        if (context == null || context.self() == null) {
            return "";
        }
        try {
            List<Message> messages = context.mailbox().readUnread(context.self().agentId()).messages();
            if (messages.isEmpty()) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            out.append("<incoming-messages>").append(System.lineSeparator());
            out.append("收到 ").append(messages.size()).append(" 条新消息:").append(System.lineSeparator());
            for (int i = 0; i < messages.size(); i++) {
                Message msg = messages.get(i);
                out.append("[").append(i + 1).append("] 来自 ")
                        .append(msg.from())
                        .append("(type=")
                        .append(msg.type().name().toLowerCase())
                        .append("): ")
                        .append(msg.summary())
                        .append(System.lineSeparator())
                        .append(Truncate.chars(msg.content(), 800))
                        .append(System.lineSeparator());
            }
            out.append("</incoming-messages>");
            return out.toString();
        } catch (Exception e) {
            return "<incoming-messages>读取团队消息失败: " + e.getMessage() + "</incoming-messages>";
        }
    }

    private void recordReadFiles(List<ToolCall> calls, List<ToolResult> results) {
        if (compactRuntime == null || calls == null || results == null) {
            return;
        }
        for (int i = 0; i < Math.min(calls.size(), results.size()); i++) {
            ToolCall call = calls.get(i);
            ToolResult result = results.get(i);
            if (!"ReadFile".equals(call.name()) || result.error()) {
                continue;
            }
            try {
                JsonNode root = JSON.readTree(call.arguments());
                if (root.path("path").isTextual()) {
                    Path path = ToolContext.withCwd(workspace).resolvePath(root.path("path").asText());
                    if (Files.isRegularFile(path)) {
                        compactRuntime.recordFileRead(path);
                    }
                }
            } catch (Exception ignored) {
                // Recovery snapshots are best-effort and should not affect tool execution.
            }
        }
    }

    private void appendToolResults(ConversationManager conversation, List<ToolCall> calls,
                                   List<ToolResult> memoryResults) {
        if (compactRuntime == null) {
            conversation.addToolResults(memoryResults);
            return;
        }
        com.study.compact.ToolResultOffloadResult offloaded;
        try {
            offloaded = ToolResultOffloader.offload(calls, memoryResults, compactRuntime);
        } catch (Exception e) {
            System.err.println("大型工具结果落盘失败，保留原始结果: " + e.getMessage());
            conversation.addToolResults(memoryResults);
            return;
        }
        conversation.addToolResults(memoryResults, offloaded.results());
        if (offloaded.changed()) {
            for (int i = 0; i < Math.min(memoryResults.size(), offloaded.results().size()); i++) {
                ToolResult original = memoryResults.get(i);
                ToolResult replacement = offloaded.results().get(i);
                if (!original.equals(replacement)) {
                    pendingMemoryToolResultReplacements.put(original.toolCallId(), replacement);
                }
            }
        }
    }

    private void alignLargeToolResultsInMemory(ConversationManager conversation) {
        if (conversation == null || pendingMemoryToolResultReplacements.isEmpty()) {
            return;
        }
        conversation.replaceToolResultsInMemory(Map.copyOf(pendingMemoryToolResultReplacements));
        pendingMemoryToolResultReplacements.clear();
    }

    private void recordPredictionBaseline(StreamCapture capture, ConversationManager conversation) {
        if (compactRuntime == null || capture == null || capture.usage == null || conversation == null) {
            return;
        }
        compactRuntime.recordPredictionBaseline(capture.usage, conversation.getMessages().size());
    }

    private void triggerMemoryUpdate(ConversationManager conversation) {
        if (memoryManager == null) {
            return;
        }
        boolean periodic = false;
        if (compactRuntime != null) {
            periodic = compactRuntime.incrementTurnCount() % 5 == 0;
        }
        memoryManager.updateAsyncIfNeeded(conversation.getMessages(), periodic);
    }

    private Outcome requestApproval(ToolCall call, String reason, CancelToken cancel, BlockingQueue<AgentEvent> out) {
        ApprovalRequest request = new ApprovalRequest(call.name(), call.arguments(), reason, "Bash".equals(call.name()));
        ApprovalHandler handler = approvalHandler;
        if (handler != null) {
            Outcome outcome = handler.request(request, cancel);
            return outcome == null ? Outcome.DENY_ONCE : outcome;
        }
        return awaitApproval(request, cancel, out);
    }

    private Outcome awaitApproval(ApprovalRequest request, CancelToken cancel, BlockingQueue<AgentEvent> out) {
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

    private boolean anyTrue(boolean[] values) {
        for (boolean value : values) {
            if (value) {
                return true;
            }
        }
        return false;
    }

    private boolean allUnknown(List<ToolCall> calls) {
        return calls.stream().allMatch(call ->
                skillToolRegistry.get(call.name()).isEmpty()
                        && registry.get(call.name()).isEmpty());
    }

    private boolean isAllowedTool(String name) {
        return allowedTools.stream().anyMatch(allowed -> allowed.equalsIgnoreCase(name));
    }

    private boolean isSystemTool(String name) {
        Tool skillTool = skillToolRegistry.get(name).orElse(null);
        if (skillTool != null) {
            return skillTool.isSystem();
        }
        Tool globalTool = registry.get(name).orElse(null);
        return globalTool != null && globalTool.isSystem();
    }

    private ExecutionOutcome executed(ToolResult result) {
        return new ExecutionOutcome(result, true);
    }

    private ExecutionOutcome notExecuted(ToolResult result) {
        return new ExecutionOutcome(result, false);
    }

    private void failContextTurn(ConversationManager conversation, TurnCheckpoint checkpoint,
                                  boolean toolExecutedThisTurn, String reason,
                                  BlockingQueue<AgentEvent> out, AtomicBoolean terminalEmitted) {
        finishContextTurn(conversation, checkpoint, toolExecutedThisTurn, reason,
                failureMarker(reason), TurnStatus.FAILED, out, terminalEmitted);
    }

    private String failureMarker(String reason) {
        String detail = reason == null || reason.isBlank() ? "未知错误" : reason.strip();
        return NOTICE_FAILED_TEMPLATE.formatted(Truncate.chars(detail, 500));
    }

    private void finishCancelled(ConversationManager conversation, TurnCheckpoint checkpoint,
                                 boolean toolExecutedThisTurn, BlockingQueue<AgentEvent> out,
                                 AtomicBoolean terminalEmitted) {
        finishContextTurn(conversation, checkpoint, toolExecutedThisTurn, NOTICE_CANCELLED,
                NOTICE_CANCELLED, TurnStatus.CANCELLED, out, terminalEmitted);
    }

    private void finishContextTurn(ConversationManager conversation, TurnCheckpoint checkpoint,
                                   boolean toolExecutedThisTurn, String reason, String assistantMarker,
                                   TurnStatus status, BlockingQueue<AgentEvent> out,
                                   AtomicBoolean terminalEmitted) {
        if (!toolExecutedThisTurn) {
            try {
                List<com.study.conversation.Message> beforeTurn = checkpoint.messagesBeforeTurn();
                List<com.study.conversation.Message> current = conversation.getMessages();
                if (hasPrefix(current, beforeTurn)) {
                    conversation.truncateTo(beforeTurn.size());
                } else {
                    conversation.replaceMessages(beforeTurn);
                }
                if (compactRuntime != null) {
                    compactRuntime.resetPredictionBaseline();
                }
                pendingMemoryToolResultReplacements.clear();
                out.add(new AgentEvent.TurnRolledBack("本轮已回滚。"));
            } catch (Exception rollbackError) {
                finish(out, terminalEmitted, TurnStatus.FAILED,
                        reason + "；会话回滚失败: " + rollbackError.getMessage());
                return;
            }
        } else {
            try {
                ensureAssistantTail(conversation, assistantMarker);
                alignLargeToolResultsInMemory(conversation);
                out.add(new AgentEvent.Notice(assistantMarker));
            } catch (Exception persistenceError) {
                finish(out, terminalEmitted, TurnStatus.FAILED,
                        reason + "；中止状态持久化失败: " + persistenceError.getMessage());
                return;
            }
        }
        finish(out, terminalEmitted, status, reason);
    }

    private static boolean hasPrefix(List<com.study.conversation.Message> messages,
                                     List<com.study.conversation.Message> prefix) {
        return messages.size() >= prefix.size()
                && messages.subList(0, prefix.size()).equals(prefix);
    }

    private void finish(BlockingQueue<AgentEvent> out, AtomicBoolean terminalEmitted,
                        TurnStatus status, String reason) {
        if (terminalEmitted.compareAndSet(false, true)) {
            out.add(new AgentEvent.Finished(status, reason));
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
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
        private boolean cancelled;
    }

    private record BatchOutcome(List<ToolResult> results, boolean completed, boolean executedAnyTool) {
    }

    private record ExecutionOutcome(ToolResult result, boolean executed) {
    }

    private record AutoSkillAction(Skill skill, String renderedBody) {
    }
}
