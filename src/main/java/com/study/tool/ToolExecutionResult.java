package com.study.tool;

public record ToolExecutionResult(String content, boolean error) {
    public ToolExecutionResult {
        if (content == null) {
            content = "";
        }
    }

    public static ToolExecutionResult ok(String content) {
        return new ToolExecutionResult(content, false);
    }

    public static ToolExecutionResult error(String content) {
        return new ToolExecutionResult(content, true);
    }
}
