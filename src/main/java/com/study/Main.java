package com.study;

import com.study.config.AppConfig;
import com.study.config.ConfigException;
import com.study.config.ConfigLoader;
import com.study.hook.HookEngine;
import com.study.hook.HookLoader;
import com.study.hook.HookRuntime;
import com.study.instructions.Loader;
import com.study.memory.MemoryManager;
import com.study.permission.PermissionEngine;
import com.study.mcp.McpConfig;
import com.study.mcp.McpManager;
import com.study.prompt.PromptBuilder;
import com.study.prompt.SkillsBlock;
import com.study.session.SessionCleaner;
import com.study.skills.ActiveSkills;
import com.study.skills.Catalog;
import com.study.agent.AgentTool;
import com.study.task.Manager;
import com.study.task.TaskGetTool;
import com.study.task.TaskListTool;
import com.study.task.TaskStopTool;
import com.study.task.TaskRespondTool;
import com.study.team.TeamManager;
import com.study.team.registry.AgentNameRegistry;
import com.study.team.tools.SendMessageTool;
import com.study.team.tools.TaskCreateTool;
import com.study.team.tools.TaskUpdateTool;
import com.study.team.tools.TeamCreateTool;
import com.study.team.tools.TeamDeleteTool;
import com.study.tool.LoadSkillTool;
import com.study.tool.Tool;
import com.study.tool.ToolRegistry;
import com.study.tui.CodeAgentModel;
import com.study.tui.tea.Program;
import com.study.worktree.WorktreeManager;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

public class Main {
    public static final String VERSION = "0.1.0";
    private static final String CONFIG_PATH = "config/code-agent.yaml";
    private static final String CONFIG_PROPERTY = "code.agent.config";

    public static void main(String[] args) {
        try {
            Path configPath = configPath();
            AppConfig config = ConfigLoader.load(configPath);
            Path root = config.getWorkspaceRoot() == null
                    ? Path.of("").toAbsolutePath().normalize()
                    : config.getWorkspaceRoot();
            String instructionText = new Loader(root).load();
            MemoryManager memoryManager = new MemoryManager(root);
            String memoryText = memoryManager.loadPromptContext();

            ToolRegistry registry = ToolRegistry.createDefault();

            McpConfig mcpConfig = com.study.mcp.ConfigLoader.loadConfig(configPath);
            McpManager mcpManager = McpManager.start(mcpConfig, VERSION);

            Runtime.getRuntime().addShutdownHook(new Thread(mcpManager::close, "mcp-shutdown"));
            for (Tool tool : mcpManager.tools()) {
                registry.register(tool);
            }

            ActiveSkills activeSkills = new ActiveSkills();
            Catalog catalog = Catalog.load(root, reservedCommands());

            com.study.subagent.Catalog subAgentCatalog = com.study.subagent.Catalog.load(root);
            WorktreeManager worktreeManager = null;
            try {
                worktreeManager = new WorktreeManager(root);
                WorktreeManager mgr = worktreeManager;
                Thread.ofVirtual().name("worktree-sweeper").start(() ->
                        mgr.sweepStale(Instant.now().minus(24, ChronoUnit.HOURS)));
            } catch (Exception e) {
                System.err.println("Worktree 管理器未启用: " + e.getMessage());
            }
            Manager taskManager = new Manager();
            Runtime.getRuntime().addShutdownHook(new Thread(taskManager::close, "task-manager-shutdown"));
            TeamManager teamManager = null;
            try {
                teamManager = new TeamManager(Path.of(System.getProperty("user.home")), root, worktreeManager,
                        taskManager, new AgentNameRegistry());
            } catch (Exception e) {
                System.err.println("Team 管理器未启用: " + e.getMessage());
            }
            HookEngine hookEngine = HookLoader.load(root);
            HookRuntime hookRuntime = new HookRuntime();
            hookEngine.dispatchSessionStartOnce(root, hookRuntime);
            registry.register(new TaskListTool(taskManager));
            registry.register(new TaskGetTool(taskManager));
            registry.register(new TaskStopTool(taskManager));
            registry.register(new TaskRespondTool(taskManager));
            registry.register(new AgentTool(subAgentCatalog, taskManager, config.isEnableSubAgentBackground(), root,
                    worktreeManager, teamManager));
            if (teamManager != null) {
                registry.register(new TeamCreateTool(teamManager));
                registry.register(new TeamDeleteTool(teamManager));
                registry.register(new TaskCreateTool());
                registry.register(new com.study.team.tools.TaskGetTool());
                registry.register(new com.study.team.tools.TaskListTool());
                registry.register(new TaskUpdateTool());
                registry.register(new SendMessageTool());
            }
            registry.register(new LoadSkillTool(catalog, activeSkills, registry));
            for (Catalog.ValidationIssue issue : catalog.validateTools(registry)) {
                System.err.println("skill " + issue.skillName() + ": allowed_tool \""
                        + issue.toolName() + "\" not registered, skipped");
                catalog.remove(issue.skillName());
            }
            String skillsCatalog = SkillsBlock.renderSkillsCatalog(catalog.toPromptItems().stream()
                    .map(item -> new SkillsBlock.SkillCatalogItem(item.name(), item.description()))
                    .toList());
            Thread.ofVirtual().name("session-cleaner").start(() ->
                    SessionCleaner.cleanExpired(root.resolve(".code-agent").resolve("sessions"), Duration.ofDays(30)));
            PermissionEngine permissionEngine = PermissionEngine.create(root);
            for (String warning : permissionEngine.warnings()) {
                System.err.println("权限配置警告: " + warning);
            }
            CodeAgentModel model = new CodeAgentModel(
                    config.getProviders(),
                    PromptBuilder.buildSystemPrompt(instructionText, memoryText, skillsCatalog),
                    registry,
                    permissionEngine,
                    null,
                    root,
                    memoryManager,
                    instructionText,
                    catalog,
                    activeSkills,
                    hookEngine,
                    hookRuntime,
                    taskManager,
                    worktreeManager,
                    teamManager);
            Program program = new Program(model);
            program.run();
        } catch (ConfigException e) {
            System.err.println("配置错误: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Path configPath() {
        String configured = System.getProperty(CONFIG_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("CODE_AGENT_CONFIG");
        }
        if (configured == null || configured.isBlank()) {
            configured = CONFIG_PATH;
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static Set<String> reservedCommands() {
        return Set.of("clear", "do", "exit", "help", "memory", "model", "permission", "plan", "resume", "session", "skill", "status",
                "worktree", "wt", "team");
    }
}
