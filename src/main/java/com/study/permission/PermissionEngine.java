package com.study.permission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.llm.ToolCall;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public final class PermissionEngine {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> READ_TOOLS = Set.of("ReadFile", "Glob", "Grep");
    private static final Set<String> WRITE_TOOLS = Set.of("WriteFile", "EditFile");

    private final Sandbox sandbox;

    private PermissionEngine(Path root) {
        this.sandbox = new Sandbox(root);
    }

    public static PermissionEngine create(Path root) {
        return new PermissionEngine(root);
    }

    public PermissionResult check(ToolCall call) {
        Category category = category(call.name());
        Map<String, Object> args = parse(call.arguments());
        if (category == Category.EXEC && Blacklist.hits(stringArg(args, "command"))) {
            return PermissionResult.deny("Command is blocked by safety blacklist.");
        }
        PermissionResult sandboxResult = checkSandbox(call.name(), category, args);
        if (sandboxResult.decision() == Decision.DENY) {
            return sandboxResult;
        }
        return switch (category) {
            case READ -> PermissionResult.allow("Read-only tool inside workspace.");
            case WRITE -> PermissionResult.ask("Tool may modify files.");
            case EXEC -> PermissionResult.ask("Shell command requires user approval.");
        };
    }

    private PermissionResult checkSandbox(String toolName, Category category, Map<String, Object> args) {
        if (category == Category.EXEC) {
            return PermissionResult.allow("shell path parsing is not enforced");
        }
        String path = stringArg(args, "path");
        if (path == null || path.isBlank()) {
            if ("Glob".equals(toolName) || "Grep".equals(toolName)) {
                path = ".";
            } else {
                return PermissionResult.deny("Missing path argument.");
            }
        }
        return sandbox.checkPath(path);
    }

    private Category category(String toolName) {
        if (READ_TOOLS.contains(toolName)) {
            return Category.READ;
        }
        if (WRITE_TOOLS.contains(toolName)) {
            return Category.WRITE;
        }
        return Category.EXEC;
    }

    private Map<String, Object> parse(String arguments) {
        try {
            return JSON.readValue(arguments == null || arguments.isBlank() ? "{}" : arguments, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String stringArg(Map<String, Object> args, String name) {
        Object value = args.get(name);
        return value == null ? "" : value.toString();
    }
}
