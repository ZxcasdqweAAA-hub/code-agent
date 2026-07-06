package com.study.conversation;

import com.study.llm.ToolCall;
import com.study.llm.ToolResult;

import java.util.List;

public record Message(String role, String content, List<ToolCall> toolCalls, List<ToolResult> toolResults) {
    public Message(String role, String content) {
        this(role, content, List.of(), List.of());
    }

    public Message {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role 不能为空");
        }
        if (content == null) {
            content = "";
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
    }

    public static Message user(String text) {
        return new Message("user", text);
    }

    public static Message assistant(String text) {
        return new Message("assistant", text);
    }

    public static Message assistantWithToolCalls(String text, List<ToolCall> calls) {
        return new Message("assistant", text, calls, List.of());
    }

    public static Message toolResults(List<ToolResult> results) {
        return new Message("tool", "", List.of(), results);
    }
}
