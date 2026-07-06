package com.study.llm;

public record ToolCall(String id, String name, String arguments) {
    public ToolCall {
        if (id == null || id.isBlank()) {
            id = "tool-call";
        }
        if (name == null) {
            name = "";
        }
        if (arguments == null || arguments.isBlank()) {
            arguments = "{}";
        }
    }
}
