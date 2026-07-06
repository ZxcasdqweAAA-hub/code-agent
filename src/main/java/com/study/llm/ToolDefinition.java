package com.study.llm;

import java.util.Map;

public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
        if (description == null) {
            description = "";
        }
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }
}
