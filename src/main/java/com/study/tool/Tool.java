package com.study.tool;

import java.util.Map;

public interface Tool {
    String name();

    String description();

    Map<String, Object> schema();

    boolean readOnly();

    default boolean isSystem() {
        return false;
    }

    default boolean shouldDefer() {
        return false;
    }

    default boolean teamOnly() {
        return false;
    }

    ToolExecutionResult execute(Map<String, Object> args);

    default ToolExecutionResult execute(ToolContext context, Map<String, Object> args) {
        return execute(args);
    }
}
