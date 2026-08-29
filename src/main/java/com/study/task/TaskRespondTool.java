package com.study.task;

import com.study.permission.Outcome;
import com.study.tool.Tool;
import com.study.tool.ToolExecutionResult;
import com.study.tool.ToolSupport;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TaskRespondTool implements Tool {
    private final Manager manager;

    public TaskRespondTool(Manager manager) {
        this.manager = manager;
    }

    @Override public String name() { return "TaskRespond"; }

    @Override public String description() { return "Respond to a pending background task approval request."; }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("task_id", ToolSupport.stringProperty("Background task id."));
        props.put("approval_id", ToolSupport.stringProperty("Pending approval id."));
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("type", "string");
        decision.put("enum", java.util.List.of("approve", "deny"));
        decision.put("description", "User decision for this approval request.");
        props.put("decision", decision);
        return ToolSupport.objectSchema(props, "task_id", "approval_id", "decision");
    }

    @Override public boolean readOnly() { return false; }
    @Override
    public ToolExecutionResult execute(Map<String, Object> args) {
        String taskId = ToolSupport.requireString(args, "task_id");
        String approvalId = ToolSupport.requireString(args, "approval_id");
        String decision = ToolSupport.requireString(args, "decision");
        Outcome outcome = "approve".equalsIgnoreCase(decision) ? Outcome.ALLOW_ONCE : Outcome.DENY_ONCE;
        if (!manager.respondApproval(taskId, approvalId, outcome)) {
            return ToolExecutionResult.error("Unknown or inactive approval request");
        }
        return ToolExecutionResult.ok("{\"status\":\"approval_recorded\"}");
    }
}
