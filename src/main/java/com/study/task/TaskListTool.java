package com.study.task;

import com.study.tool.Tool;
import com.study.tool.ToolExecutionResult;

import java.util.Map;

public final class TaskListTool implements Tool {
    private final Manager manager;

    public TaskListTool(Manager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "TaskList";
    }

    @Override
    public String description() {
        return "List background subagent tasks.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public boolean isSystem() {
        return true;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args) {
        return ToolExecutionResult.ok(TaskJson.write(manager.list().stream().map(TaskJson::summary).toList()));
    }
}
