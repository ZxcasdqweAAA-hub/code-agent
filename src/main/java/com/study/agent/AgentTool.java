package com.study.agent;

import com.study.conversation.ConversationManager;
import com.study.subagent.Catalog;
import com.study.subagent.Definition;
import com.study.task.Manager;
import com.study.team.Team;
import com.study.team.TeamManager;
import com.study.tool.Filter;
import com.study.tool.Tool;
import com.study.tool.ToolExecutionResult;
import com.study.tool.ToolSupport;
import com.study.tool.Truncate;
import com.study.worktree.AutoCleanupReport;
import com.study.worktree.GitHelper;
import com.study.worktree.Worktree;
import com.study.worktree.WorktreeManager;
import com.study.worktree.WorktreeNaming;
import com.study.permission.Mode;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentTool implements Tool {
    private final Catalog catalog;
    private final Manager taskManager;
    private final boolean backgroundEnabled;
    private final Path workspace;
    private final WorktreeManager worktreeManager;
    private final TeamManager teamManager;

    public AgentTool(Catalog catalog, Manager taskManager, boolean backgroundEnabled, Path workspace) {
        this(catalog, taskManager, backgroundEnabled, workspace, null, null);
    }

    public AgentTool(Catalog catalog, Manager taskManager, boolean backgroundEnabled, Path workspace,
                     WorktreeManager worktreeManager) {
        this(catalog, taskManager, backgroundEnabled, workspace, worktreeManager, null);
    }

    public AgentTool(Catalog catalog, Manager taskManager, boolean backgroundEnabled, Path workspace,
                     WorktreeManager worktreeManager, TeamManager teamManager) {
        this.catalog = catalog;
        this.taskManager = taskManager;
        this.backgroundEnabled = backgroundEnabled;
        this.workspace = workspace == null ? Path.of("").toAbsolutePath().normalize() : workspace.toAbsolutePath().normalize();
        this.worktreeManager = worktreeManager;
        this.teamManager = teamManager;
    }

    @Override
    public String name() {
        return "Agent";
    }

    @Override
    public String description() {
        StringBuilder out = new StringBuilder();
        out.append("Launch a focused subagent. Use subagent_type to select a predefined role, or choose fork to fork from the current conversation context. ");
        out.append("Fork mode always runs in background. ");
        out.append("Subagents use the current workspace by default; a role configured with isolation: worktree runs in an isolated Git worktree. ");
        out.append("subagent_type options: ");
        out.append(String.join(", ", schemaTypeNames()));
        return out.toString();
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("prompt", ToolSupport.stringProperty("Task instruction for the subagent."));
        props.put("description", ToolSupport.stringProperty("Short task description for UI/status display."));
        Map<String, Object> type = new LinkedHashMap<>();
        type.put("type", "string");
        type.put("enum", schemaTypeNames());
        type.put("description", schemaTypeDescription());
        props.put("subagent_type", type);
        props.put("name", ToolSupport.stringProperty("Optional task name for background tracking."));
        props.put("teamName", ToolSupport.stringProperty("Optional Agent Team name. When set, spawn a persistent teammate in that team."));
        props.put("run_in_background", Map.of("type", "boolean", "description", "Launch in background and return a task id."));
        return ToolSupport.objectSchema(props, "prompt", "description");
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args) {
        return ToolExecutionResult.error("Agent tool requires runtime context.");
    }

    public ToolExecutionResult execute(Context context, Map<String, Object> args) {
        try {
            String prompt = ToolSupport.requireString(args, "prompt");
            String description = ToolSupport.requireString(args, "description");
            String teamName = ToolSupport.optionalString(args, "teamName", "");
            if (!teamName.isBlank()) {
                return executeTeamSpawn(context, args, prompt, description, teamName);
            }
            if (context.subAgent() || Fork.isForkContext(context.conversation().getMessages())) {
                return ToolExecutionResult.error("subagent cannot spawn Agent");
            }
            String type = ToolSupport.optionalString(args, "subagent_type", "");
            Definition definition = type.isBlank() || type.equalsIgnoreCase("fork")
                    ? catalog.forkDefinition()
                    : catalog.resolve(type).orElse(null);
            if (definition == null) {
                return ToolExecutionResult.error("unknown subagent_type: " + type);
            }
            boolean background = definition.background() || bool(args.get("run_in_background")) || definition.isFork();
            if (background && !backgroundEnabled) {
                return ToolExecutionResult.error("background mode is disabled by config");
            }
            if ("worktree".equals(definition.isolation()) && worktreeManager == null) {
                return ToolExecutionResult.error("subagent isolation=worktree requires an enabled WorktreeManager");
            }
            List<String> allowed = Filter.applyAgentToolFilter(new Filter.FilterParams(
                    context.parent().registry().names(),
                    definition.source().ordinal() + 1,
                    background,
                    definition.tools(),
                    definition.disallowedTools()
            ));
            ConversationManager childConversation = new ConversationManager();
            String task = prompt;
            if (definition.isFork()) {
                childConversation.copyFrom(Fork.buildForkedMessages(context.conversation().getMessages(), prompt));
                task = "";
            }
            Path childWorkspace = "worktree".equals(definition.isolation()) ? null : workspace;
            Agent child = new Agent(
                    context.parent().client(),
                    context.parent().registry(),
                    context.parent().version(),
                    context.parent().permissions(),
                    null,
                    childSystemPrompt(context.parent(), definition),
                    null,
                    null,
                    allowed,
                    context.parent().hookEngine(),
                    context.parent().hookRuntime(),
                    definition.maxTurns(),
                    definition.dontAsk(),
                    true,
                    definition.name(),
                    childWorkspace == null ? workspace : childWorkspace,
                    parentSessionId(context.parent())
            );
            if ("worktree".equals(definition.isolation())) {
                return executeWithWorktree(context, definition, childConversation, task, allowed, background,
                        nameOrDescription(args, description));
            }
            if (background) {
                String id = taskManager.nextTaskId();
                child.setRunMode(resolveMode(context.mode(), definition.permissionMode()));
                child.setApprovalHandler((request, childCancel) -> taskManager.awaitApproval(id, request, childCancel));
                taskManager.launchWithId(id, child, childConversation, nameOrDescription(args, description), task);
                return ToolExecutionResult.ok("{\"task_id\":\"" + id + "\",\"status\":\"async_launched\"}");
            }
            child.setApprovalHandler(context.approvalHandler());
            child.setRunMode(resolveMode(context.mode(), definition.permissionMode()));
            String result = child.runToCompletion(childConversation, task, new CancelToken(),
                    resolveMode(context.mode(), definition.permissionMode()));
            return ToolExecutionResult.ok(result);
        } catch (Exception e) {
            return ToolExecutionResult.error("subagent error: " + e.getMessage());
        }
    }

    private ToolExecutionResult executeTeamSpawn(Context context, Map<String, Object> args, String prompt,
                                                 String description, String teamName) {
        try {
            if (teamManager == null) {
                return ToolExecutionResult.error("Agent Team is not enabled");
            }
            if (context.subAgent()) {
                return ToolExecutionResult.error("teammate/subagent cannot spawn team members");
            }
            Team team = teamManager.get(teamName).orElse(null);
            if (team == null) {
                return ToolExecutionResult.error("Team not found: " + teamName);
            }
            String type = ToolSupport.optionalString(args, "subagent_type", "");
            Definition definition = type.isBlank()
                    ? catalog.resolve("general-purpose").orElse(catalog.forkDefinition())
                    : catalog.resolve(type).orElse(null);
            if (definition == null) {
                return ToolExecutionResult.error("unknown subagent_type: " + type);
            }
            String memberName = ToolSupport.optionalString(args, "name", "");
            if (memberName.isBlank()) {
                memberName = nameOrDescription(args, description);
            }
            List<String> allowed = Filter.applyAgentToolFilter(new Filter.FilterParams(
                    context.parent().registry().names(),
                    definition.source().ordinal() + 1,
                    false,
                    definition.tools(),
                    definition.disallowedTools()
            ));
            TeamManager.Spawned spawned = teamManager.spawnTeammate(team, context.parent(), definition,
                    memberName, prompt, allowed);
            return ToolExecutionResult.ok("""
                    {"memberName":"%s","agentId":"%s","worktree":"%s","backend":"%s","paneId":""}
                    """.formatted(spawned.info().name(), spawned.info().agentId(), spawned.info().worktreePath(),
                    spawned.info().backendType().wireValue()).strip());
        } catch (Exception e) {
            return ToolExecutionResult.error("team spawn error: " + e.getMessage());
        }
    }

    private ToolExecutionResult executeWithWorktree(Context context, Definition definition,
                                                    ConversationManager childConversation, String task,
                                                    List<String> allowed, boolean background, String displayName) {
        String name = WorktreeNaming.randomAgentName();
        if (GitHelper.hasUncommittedChanges(worktreeManager.repoRoot())) {
            return ToolExecutionResult.error("cannot create subagent worktree: parent worktree has uncommitted changes");
        }
        try {
            Worktree wt = worktreeManager.create(name, "HEAD", false);
            String notice = buildWorktreeNotice(context.parent().workspace(), wt.path());
            Agent child = new Agent(
                    context.parent().client(),
                    context.parent().registry(),
                    context.parent().version(),
                    context.parent().permissions(),
                    null,
                    childSystemPrompt(context.parent(), definition),
                    null,
                    null,
                    allowed,
                    context.parent().hookEngine(),
                    context.parent().hookRuntime(),
                    definition.maxTurns(),
                    definition.dontAsk(),
                    true,
                    definition.name(),
                    wt.path(),
                    parentSessionId(context.parent())
            );
            child.setRunMode(resolveMode(context.mode(), definition.permissionMode()));
            String childTask = notice + (task == null || task.isBlank() ? "" : System.lineSeparator() + System.lineSeparator() + task);
            if (background) {
                String id = taskManager.nextTaskId();
                child.setApprovalHandler((request, childCancel) -> taskManager.awaitApproval(id, request, childCancel));
                taskManager.launchWithId(id, child, childConversation, displayName, childTask,
                        () -> {
                            try { worktreeManager.autoCleanup(name); }
                            catch (Exception e) { throw new RuntimeException(e); }
                        });
                return ToolExecutionResult.ok("{\"task_id\":\"" + id + "\",\"status\":\"async_launched\",\"worktree\":\"" + jsonEscape(wt.path().toString()) + "\",\"branch\":\"" + jsonEscape(wt.branch()) + "\"}");
            }
            child.setApprovalHandler(context.approvalHandler());
            String result = "";
            try {
                result = child.runToCompletion(childConversation, childTask, context.cancel(),
                        resolveMode(context.mode(), definition.permissionMode()));
            } finally {
                // Always inspect/clean the temporary worktree, including failures and cancellation.
                AutoCleanupReport cleanup = worktreeManager.autoCleanup(name);
                if (cleanup.kept()) {
                    result += System.lineSeparator()
                            + "[Worktree 保留: " + cleanup.path() + "，分支 " + cleanup.branch() + "]";
                }
            }
            return ToolExecutionResult.ok(result);
        } catch (Exception e) {
            return ToolExecutionResult.error("subagent worktree error: " + e.getMessage());
        }
    }

    private String buildWorktreeNotice(Path parentCwd, Path wtPath) {
        return """
                <worktree-context>
                你当前在一个独立的 Git Worktree 副本中工作，与父 Agent 的工作目录隔离。
                - 父目录: %s
                - 你的工作目录: %s
                - 父 Agent 提到的绝对路径如果位于父目录下，需要替换成你的工作目录路径后再读写。
                - 编辑文件前，必须先在本地 Worktree 重新 ReadFile 一次，避免使用过时内容。
                </worktree-context>
                """.formatted(parentCwd, wtPath).strip();
    }

    private static String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String childSystemPrompt(Agent parent, Definition definition) {
        String base = (definition.isFork() || definition.systemPrompt().isBlank())
                ? parent.systemPrompt()
                : definition.systemPrompt();
        return base + """

                SubAgent reporting requirements:
                - A response containing one or more tool calls is not a final report; the task is still in progress.
                - Whenever you emit tool calls, briefly state "Completed work" for work finished before those calls and "Remaining work" for the calls and later work still required.
                - If you changed files, include a "Changed files:" section listing each path and what changed.
                - Include a "Commands run:" section for any shell commands or test commands you ran.
                - Include a "Tests:" section with pass/fail/not-run status.
                - Keep the final result concise because the main Agent only receives your final result, not your internal loop.
                """;
    }

    private List<String> schemaTypeNames() {
        List<String> names = new java.util.ArrayList<>();
        names.add("fork");
        names.addAll(catalog.list().stream().map(Definition::name).toList());
        return List.copyOf(names);
    }

    private String schemaTypeDescription() {
        StringBuilder out = new StringBuilder("Select the subagent type:\n- fork: Fork the current conversation context and run it independently in background.\n"
                + "Isolation: roles use the current workspace by default; roles configured with isolation: worktree use an isolated Git worktree, run in foreground, and may keep changes in a separate branch.\n");
        for (Definition definition : catalog.list()) {
            out.append("- ").append(definition.name()).append(": ")
                    .append(definition.description() == null ? "" : definition.description())
                    .append('\n');
        }
        return out.toString().strip();
    }

    private String nameOrDescription(Map<String, Object> args, String description) {
        String name = ToolSupport.optionalString(args, "name", "");
        return name.isBlank() ? Truncate.chars(description, 60) : name;
    }

    private String parentSessionId(Agent parent) {
        return parent.compactRuntime() == null || parent.compactRuntime().session() == null
                ? ""
                : parent.compactRuntime().session().sessionId();
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    Mode resolveMode(Mode parentMode, String configured) {
        if (configured == null || configured.isBlank() || "inherit".equalsIgnoreCase(configured)) {
            return parentMode == null ? Mode.DEFAULT : parentMode;
        }
        if ("plan".equalsIgnoreCase(configured)) {
            return Mode.PLAN;
        }
        return Mode.parseConfigurable(configured).orElse(parentMode == null ? Mode.DEFAULT : parentMode);
    }

    public record Context(Agent parent, ConversationManager conversation, CancelToken cancel, boolean subAgent,
                          Mode mode, ApprovalHandler approvalHandler) {
        public Context(Agent parent, ConversationManager conversation, CancelToken cancel, boolean subAgent) {
            this(parent, conversation, cancel, subAgent, Mode.DEFAULT, null);
        }

        public Context(Agent parent, ConversationManager conversation, CancelToken cancel, boolean subAgent, Mode mode) {
            this(parent, conversation, cancel, subAgent, mode, null);
        }
    }
}
