package com.study.llm;

public record ToolResult(String toolCallId, String content, boolean error) {
    public ToolResult {
        if (toolCallId == null || toolCallId.isBlank()) {
            toolCallId = "tool-call";
        }
        if (content == null) {
            content = "";
        }
    }
}
