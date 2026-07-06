package com.study.tool;

import java.util.Map;

public interface Tool {
    String name();

    String description();

    Map<String, Object> schema();

    boolean readOnly();

    ToolExecutionResult execute(Map<String, Object> args);
}
