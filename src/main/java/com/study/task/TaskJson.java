package com.study.task;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

final class TaskJson {
    static final ObjectMapper JSON = new ObjectMapper();

    private TaskJson() {
    }

    static Map<String, Object> summary(BackgroundTask task) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", task.id());
        out.put("name", task.name());
        out.put("status", task.status().name().toLowerCase());
        out.put("task", task.task());
        out.put("start_time", task.startTime() == null ? "" : task.startTime().toString());
        out.put("end_time", task.endTime() == null ? "" : task.endTime().toString());
        return out;
    }

    static Map<String, Object> detail(BackgroundTask task) {
        Map<String, Object> out = summary(task);
        out.put("result", task.result());
        out.put("error", task.error());
        PendingApproval pending = task.pendingApproval();
        if (pending != null) {
            Map<String, Object> approval = new LinkedHashMap<>();
            approval.put("approval_id", pending.id());
            approval.put("tool", pending.toolName());
            approval.put("arguments", pending.arguments());
            approval.put("reason", pending.reason());
            out.put("pending_approval", approval);
        }
        return out;
    }

    static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
