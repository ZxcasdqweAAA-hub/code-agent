package com.study.task;

import com.study.tool.Tool;
import com.study.tool.ToolExecutionResult;
import com.study.tool.ToolSupport;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TaskStopTool implements Tool {
    private final Manager manager;

    public TaskStopTool(Manager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "TaskStop";
    }

    @Override
    public String description() {
        return "Request cancellation for a background subagent task.";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("task_id", ToolSupport.stringProperty("Task id returned by Agent."));
        return ToolSupport.objectSchema(props, "task_id");
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public boolean isSystem() {
        return true;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args) {
        String id = ToolSupport.requireString(args, "task_id");
        if (!manager.stop(id)) {
            return ToolExecutionResult.error("Unknown task_id: " + id);
        }
        return ToolExecutionResult.ok("{\"status\":\"cancellation_requested\"}");
    }
}
