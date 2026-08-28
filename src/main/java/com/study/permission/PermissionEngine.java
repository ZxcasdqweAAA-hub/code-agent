package com.study.permission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.llm.ToolCall;
import com.study.tool.Tool;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class PermissionEngine {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FILE_READ_TOOLS = Set.of("ReadFile", "Glob", "Grep");
    private static final Set<String> FILE_WRITE_TOOLS = Set.of("WriteFile", "EditFile");

    private final Sandbox sandbox;
    private final Path personalPath;
    private final Path projectPath;
    private final RuleSet projectRules;
    private volatile RuleSet personalRules;
    private final Mode startMode;
    private final List<String> warnings;

    private PermissionEngine(Path workspace, Path userHome) {
        this.sandbox = new Sandbox(workspace);
        Path safeHome = userHome == null
                ? Path.of(System.getProperty("user.home"))
                : userHome.toAbsolutePath().normalize();
        this.personalPath = safeHome.resolve(".code-agent").resolve("settings.yaml");
        this.projectPath = sandbox.root().resolve(".code-agent").resolve("settings.yaml");

        SettingsLoader.LoadedSettings personal = SettingsLoader.load(personalPath);
        SettingsLoader.LoadedSettings project = SettingsLoader.load(projectPath);
        this.personalRules = toRuleSet(personal.settings());
        this.projectRules = toRuleSet(project.settings());
        this.startMode = configuredMode(project.settings())
                .or(() -> configuredMode(personal.settings()))
                .orElse(Mode.DEFAULT);
        List<String> allWarnings = new ArrayList<>(personal.warnings());
        allWarnings.addAll(project.warnings());
        this.warnings = List.copyOf(allWarnings);
    }

    public static PermissionEngine create(Path workspace) {
        return new PermissionEngine(workspace, Path.of(System.getProperty("user.home")));
    }

    public static PermissionEngine create(Path workspace, Path userHome) {
        return new PermissionEngine(workspace, userHome);
    }

    public Mode startMode() {
        return startMode;
    }

    public List<String> warnings() {
        return warnings;
    }

    public Path workspace() {
        return sandbox.root();
    }

    public Path personalPath() {
        return personalPath;
    }

    public Path projectPath() {
        return projectPath;
    }

    public PermissionResult check(Mode mode, ToolCall call, Tool tool) {
        return check(mode, call, tool, null);
    }

    public PermissionResult check(Mode mode, ToolCall call, Tool tool, Path executionRoot) {
        Mode effectiveMode = mode == null ? Mode.DEFAULT : mode;
        if (tool != null && tool.isSystem()) {
            return PermissionResult.allow("system tool");
        }
        ToolCall safeCall = call == null ? new ToolCall("", "", "{}") : call;
        Category category = category(safeCall.name(), tool);
        ParsedArguments parsed = parse(safeCall.arguments());
        Sandbox pathSandbox = executionRoot == null ? sandbox : new Sandbox(executionRoot);

        if (category == Category.BASH) {
            String command = stringArg(parsed.values(), "command");
            if (!command.isBlank() && Blacklist.hits(command)) {
                return PermissionResult.deny("命中危险 Bash 命令黑名单");
            }
        }

        if (effectiveMode == Mode.PLAN) {
            return checkPlan(category, safeCall.name(), parsed, pathSandbox);
        }

        if (category == Category.FILE_READ || category == Category.FILE_WRITE) {
            PermissionResult pathResult = checkFilePath(effectiveMode, safeCall.name(), parsed, pathSandbox);
            if (pathResult != null) {
                return pathResult;
            }
        }

        if (category == Category.BASH) {
            String command = stringArg(parsed.values(), "command");
            if (!command.isBlank()) {
                Optional<RuleSet.RuleMatch> project = projectRules.match(command);
                if (project.isPresent()) {
                    return fromRule("项目级", project.get());
                }
                Optional<RuleSet.RuleMatch> personal = personalRules.match(command);
                if (personal.isPresent()) {
                    return fromRule("个人级", personal.get());
                }
            }
        }

        return modeFallback(effectiveMode, category);
    }

    public synchronized void persistPersonalAllow(ToolCall call) throws IOException {
        if (call == null || !"Bash".equals(call.name())) {
            throw new IllegalArgumentException("只有 Bash 调用可以永久允许");
        }
        ParsedArguments parsed = parse(call.arguments());
        String command = parsed.ok() ? stringArg(parsed.values(), "command") : "";
        if (command.isBlank()) {
            throw new IllegalArgumentException("Bash command 不能为空");
        }
        if (Blacklist.hits(command)) {
            throw new IllegalArgumentException("危险 Bash 命令不能永久允许");
        }
        Rule exact = Rule.exact(command, Decision.ALLOW);
        String rendered = exact.render();

        SettingsLoader.LoadedSettings current = SettingsLoader.load(personalPath);
        LinkedHashMap<String, Object> document = new LinkedHashMap<>(current.document());
        LinkedHashMap<String, Object> permissions = mutableMap(document.get("permissions"));
        List<Object> allow = mutableList(permissions.get("allow"));
        if (allow.stream().noneMatch(rendered::equals)) {
            allow.add(rendered);
        }
        permissions.put("allow", allow);
        document.put("permissions", permissions);

        Path parent = personalPath.getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "settings-", ".tmp");
        try {
            Files.writeString(temp, new Yaml().dump(document));
            try {
                Files.move(temp, personalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, personalPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }

        SettingsLoader.LoadedSettings reloaded = SettingsLoader.load(personalPath);
        this.personalRules = toRuleSet(reloaded.settings());
    }

    private PermissionResult checkPlan(Category category, String toolName, ParsedArguments parsed,
                                       Sandbox pathSandbox) {
        return switch (category) {
            case FILE_READ -> {
                PermissionResult pathResult = checkFilePath(Mode.PLAN, toolName, parsed, pathSandbox);
                yield pathResult == null ? PermissionResult.allow("Plan 允许只读文件工具") : pathResult;
            }
            case READ_ONLY -> PermissionResult.allow("Plan 允许只读工具");
            case FILE_WRITE -> PermissionResult.deny("Plan 只读模式禁止修改文件");
            case BASH -> PermissionResult.deny("Plan 只读模式禁止执行 Bash");
            case OTHER -> PermissionResult.deny("Plan 只读模式禁止有副作用或未知工具");
        };
    }

    private PermissionResult checkFilePath(Mode mode, String toolName, ParsedArguments parsed,
                                           Sandbox pathSandbox) {
        if (!parsed.ok()) {
            return mode == Mode.BYPASS_PERMISSIONS
                    ? null
                    : PermissionResult.ask("无法解析文件路径参数，需要用户确认");
        }
        String path = stringArg(parsed.values(), "path");
        if (path.isBlank() && ("Glob".equals(toolName) || "Grep".equals(toolName))) {
            path = ".";
        }
        if (path.isBlank()) {
            return mode == Mode.BYPASS_PERMISSIONS
                    ? null
                    : PermissionResult.ask("缺少文件路径参数，需要用户确认");
        }
        Sandbox.PathResult inspected = pathSandbox.inspect(path);
        if (inspected.status() == Sandbox.PathStatus.INSIDE) {
            return null;
        }
        if (mode == Mode.BYPASS_PERMISSIONS) {
            return null;
        }
        return PermissionResult.ask(inspected.status() == Sandbox.PathStatus.OUTSIDE
                ? "访问工作区外路径，需要用户确认: " + path
                : "无法可靠解析文件路径，需要用户确认: " + path);
    }

    private PermissionResult modeFallback(Mode mode, Category category) {
        if (mode == Mode.BYPASS_PERMISSIONS) {
            return PermissionResult.allow("bypassPermissions 模式自动允许");
        }
        if (category == Category.FILE_READ || category == Category.READ_ONLY) {
            return PermissionResult.allow(mode.displayName() + " 模式允许只读操作");
        }
        if (mode == Mode.ACCEPT_EDITS && category == Category.FILE_WRITE) {
            return PermissionResult.allow("acceptEdits 模式允许工作区内文件修改");
        }
        return switch (category) {
            case FILE_WRITE -> PermissionResult.ask(mode.displayName() + " 模式下文件修改需要用户确认");
            case BASH -> PermissionResult.ask(mode.displayName() + " 模式下 Bash 需要用户确认");
            case OTHER -> PermissionResult.ask("未知或有副作用的工具需要用户确认");
            case FILE_READ, READ_ONLY -> PermissionResult.allow("read-only tool");
        };
    }

    private PermissionResult fromRule(String layer, RuleSet.RuleMatch match) {
        String reason = "命中" + layer + " Bash "
                + (match.decision() == Decision.DENY ? "deny" : "allow")
                + " 规则: " + match.rule().render();
        return match.decision() == Decision.DENY
                ? PermissionResult.deny(reason)
                : PermissionResult.allow(reason);
    }

    private Category category(String toolName, Tool tool) {
        if (FILE_READ_TOOLS.contains(toolName)) {
            return Category.FILE_READ;
        }
        if (FILE_WRITE_TOOLS.contains(toolName)) {
            return Category.FILE_WRITE;
        }
        if ("Bash".equals(toolName)) {
            return Category.BASH;
        }
        if (tool != null && tool.readOnly()) {
            return Category.READ_ONLY;
        }
        return Category.OTHER;
    }

    private static RuleSet toRuleSet(Settings settings) {
        List<Rule> allow = settings.allow().stream()
                .map(value -> Rule.parse(value, Decision.ALLOW).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        List<Rule> deny = settings.deny().stream()
                .map(value -> Rule.parse(value, Decision.DENY).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        return RuleSet.of(allow, deny);
    }

    private static Optional<Mode> configuredMode(Settings settings) {
        return Mode.parseConfigurable(settings.defaultMode());
    }

    private static LinkedHashMap<String, Object> mutableMap(Object value) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(entry.getKey().toString(), entry.getValue());
                }
            }
        }
        return out;
    }

    private static List<Object> mutableList(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
    }

    private ParsedArguments parse(String arguments) {
        try {
            Map<String, Object> values = JSON.readValue(
                    arguments == null || arguments.isBlank() ? "{}" : arguments,
                    new TypeReference<>() {
                    });
            return new ParsedArguments(values, true);
        } catch (Exception e) {
            return new ParsedArguments(Map.of(), false);
        }
    }

    private String stringArg(Map<String, Object> args, String name) {
        Object value = args.get(name);
        return value == null ? "" : value.toString();
    }

    private record ParsedArguments(Map<String, Object> values, boolean ok) {
    }
}
