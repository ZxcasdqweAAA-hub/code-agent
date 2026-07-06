package com.study.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ToolRegistry {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> order = new ArrayList<>();
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) {
        if (!tools.containsKey(tool.name())) {
            order.add(tool.name());
        }
        tools.put(tool.name(), tool);
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<Map<String, Object>> getAllSchemas(String protocol) {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (String name : order) {
            Tool tool = tools.get(name);
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

    public boolean isReadOnly(String name) {
        return get(name).map(Tool::readOnly).orElse(false);
    }

    public ToolExecutionResult execute(String name, String arguments) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return ToolExecutionResult.error("未知工具: " + name);
        }
        try {
            return tool.execute(parseArguments(arguments));
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
        return registry;
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
