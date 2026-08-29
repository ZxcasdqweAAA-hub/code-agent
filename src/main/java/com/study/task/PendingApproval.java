package com.study.task;

import com.study.agent.ApprovalRequest;

public record PendingApproval(String id, ApprovalRequest request) {
    public String toolName() {
        return request.name();
    }

    public String arguments() {
        return request.arguments();
    }

    public String reason() {
        return request.reason();
    }
}
