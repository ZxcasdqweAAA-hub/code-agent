package com.study.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class ToolRegistry {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> order = new ArrayList<>();
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Set<String> discoveredDeferred = new HashSet<>();

    public void register(Tool tool) {
        if (!tools.containsKey(tool.name())) {
            order.add(tool.name());
        }
        tools.put(tool.name(), tool);
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public int count() {
        return tools.size();
    }

    public List<String> names() {
        return List.copyOf(order);
    }

    public List<Map<String, Object>> getAllSchemas(String protocol) {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (String name : order) {
            Tool tool = tools.get(name);
            if (isDeferred(tool) || tool.teamOnly()) {
                continue;
            }
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("name", tool.name());
            definition.put("description", tool.description());
            definition.put("input_schema", tool.schema());
            definition.put("parameters", tool.schema());
            definitions.add(definition);
        }
        return definitions;
    }

    public List<Map<String, Object>> readOnlyDefinitions() {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (String name : order) {
            Tool tool = tools.get(name);
            if (isDeferred(tool) || tool.teamOnly()) {
                continue;
            }
            if (tool.readOnly()) {
                Map<String, Object> definition = new LinkedHashMap<>();
                definition.put("name", tool.name());
                definition.put("description", tool.description());
                definition.put("input_schema", tool.schema());
                definition.put("parameters", tool.schema());
                definitions.add(definition);
            }
        }
        return definitions;
    }

    public List<Map<String, Object>> planDefinitions() {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (String name : order) {
            Tool tool = tools.get(name);
            if (isDeferred(tool) || tool.teamOnly()) {
                continue;
            }
            if (tool.readOnly() || tool.isSystem()) {
                definitions.add(definition(tool));
            }
        }
        return definitions;
    }

    public List<Map<String, Object>> definitionsFiltered(List<String> allowed, String protocol) {
        if (allowed == null || allowed.isEmpty()) {
            return getAllSchemas(protocol);
        }
        Set<String> allowedSet = new HashSet<>(allowed);
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (String name : order) {
            Tool tool = tools.get(name);
            if (isDeferred(tool)) {
                continue;
            }
            if (tool.teamOnly() && !allowedSet.contains(tool.name())) {
                continue;
            }
            if (tool.isSystem() || allowedSet.contains(tool.name())) {
                definitions.add(definition(tool));
            }
        }
        return definitions;
    }

    public void registerSkillTool(Tool tool) {
        register(tool);
    }

    public void clear() {
        order.clear();
        tools.clear();
        discoveredDeferred.clear();
    }

    public boolean isReadOnly(String name) {
        return get(name).map(Tool::readOnly).orElse(false);
    }

    public List<String> getDeferredToolNames() {
        return order.stream()
                .map(tools::get)
                .filter(this::isDeferred)
                .map(Tool::name)
                .sorted()
                .toList();
    }

    public List<Tool> getDeferredTools() {
        return order.stream()
                .map(tools::get)
                .filter(this::isDeferred)
                .sorted(Comparator.comparing(Tool::name))
                .toList();
    }

    public void markDiscovered(String name) {
        Tool tool = tools.get(name);
        if (tool != null && tool.shouldDefer()) {
            discoveredDeferred.add(name);
        }
    }

    public List<Map<String, Object>> findDeferredByNames(List<String> names, String protocol) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> schemas = new ArrayList<>();
        for (String rawName : names) {
            if (rawName == null) {
                continue;
            }
            String name = rawName.trim();
            Tool tool = tools.get(name);
            if (tool != null && isDeferred(tool)) {
                schemas.add(definition(tool));
            }
        }
        return schemas;
    }

    public List<Map<String, Object>> searchDeferred(String query, int maxResults, String protocol) {
        String q = query == null ? "" : query.toLowerCase();
        int limit = Math.max(1, Math.min(maxResults, 20));
        return getDeferredTools().stream()
                .filter(tool -> tool.name().toLowerCase().contains(q)
                        || tool.description().toLowerCase().contains(q))
                .limit(limit)
                .map(this::definition)
                .collect(Collectors.toList());
    }

    public String deferredToolsReminder() {
        List<String> names = getDeferredToolNames();
        if (names.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Some specialized tools are deferred and are not listed in your initial tool set. ");
        sb.append("If you need one of these unavailable tools, use ToolSearch to find and load it first. ");
        sb.append("Use query \"select:<name>[,<name>...]\" to load exact tool schemas before calling them. ");
        sb.append("For example, use query \"select:AskUserQuestion\" to load a user-question tool if it is listed below.\n");
        sb.append("Deferred tools available via ToolSearch:\n");
        for (String name : names) {
            sb.append(name).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    public ToolExecutionResult execute(String name, String arguments) {
        return execute(ToolContext.root(), name, arguments);
    }

    public ToolExecutionResult execute(ToolContext context, String name, String arguments) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return ToolExecutionResult.error("未知工具: " + name);
        }
        try {
            return tool.execute(context == null ? ToolContext.root() : context, parseArguments(arguments));
        } catch (RuntimeException e) {
            return ToolExecutionResult.error("工具执行失败: " + e.getMessage());
        }
    }

    public static ToolRegistry createDefault() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new EditFileTool());
        registry.register(new BashTool());
        registry.register(new GlobTool());
        registry.register(new GrepTool());
        registry.register(new ToolSearchTool(registry));
        return registry;
    }

    private boolean isDeferred(Tool tool) {
        return tool != null && tool.shouldDefer() && !discoveredDeferred.contains(tool.name());
    }

    private Map<String, Object> definition(Tool tool) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", tool.name());
        definition.put("description", tool.description());
        definition.put("input_schema", tool.schema());
        definition.put("parameters", tool.schema());
        return definition;
    }

    private Map<String, Object> parseArguments(String arguments) {
        String json = arguments == null || arguments.isBlank() ? "{}" : arguments;
        try {
            return JSON.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of("_parse_error", e.getMessage(), "_raw", json);
        }
    }
}
