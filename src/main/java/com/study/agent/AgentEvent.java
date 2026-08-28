package com.study.agent;

public sealed interface AgentEvent permits AgentEvent.Text, AgentEvent.Tool, AgentEvent.Approval, AgentEvent.UsageReport, AgentEvent.Iter, AgentEvent.Notice, AgentEvent.Compact, AgentEvent.TurnRolledBack, AgentEvent.Finished {
    record Text(String delta) implements AgentEvent {
    }

    record Tool(ToolEvent event) implements AgentEvent {
    }

    record Approval(ApprovalRequest request) implements AgentEvent {
    }

    record UsageReport(long inputTokens, long outputTokens, long cacheWrite, long cacheRead) implements AgentEvent {
    }

    record Iter(int value) implements AgentEvent {
    }

    record Notice(String message) implements AgentEvent {
    }

    record Compact(long beforeTokens, long afterTokens, String message) implements AgentEvent {
    }

    record TurnRolledBack(String message) implements AgentEvent {
    }

    record Finished(TurnStatus status, String reason) implements AgentEvent {
        public Finished {
            status = status == null ? TurnStatus.FAILED : status;
            reason = reason == null ? "" : reason;
        }
    }
}
