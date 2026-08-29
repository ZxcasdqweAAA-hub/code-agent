package com.study.task;

import com.study.tool.Tool;
import com.study.tool.ToolExecutionResult;
import com.study.tool.ToolSupport;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TaskGetTool implements Tool {
    private final Manager manager;

    public TaskGetTool(Manager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "TaskGet";
    }

    @Override
    public String description() {
        return "Get a background subagent task by task_id.";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("task_id", ToolSupport.stringProperty("Task id returned by Agent."));
        return ToolSupport.objectSchema(props, "task_id");
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
        String id = ToolSupport.requireString(args, "task_id");
        return manager.get(id)
                .map(task -> ToolExecutionResult.ok(TaskJson.write(TaskJson.detail(task))))
                .orElseGet(() -> ToolExecutionResult.error("Unknown task_id: " + id));
    }
}
