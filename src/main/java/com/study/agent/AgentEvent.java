package com.study.agent;

public sealed interface AgentEvent permits AgentEvent.Text, AgentEvent.Tool, AgentEvent.UsageReport, AgentEvent.Iter, AgentEvent.Notice, AgentEvent.Done, AgentEvent.Failed {
    record Text(String delta) implements AgentEvent {
    }

    record Tool(ToolEvent event) implements AgentEvent {
    }

    record UsageReport(long inputTokens, long outputTokens) implements AgentEvent {
    }

    record Iter(int value) implements AgentEvent {
    }

    record Notice(String message) implements AgentEvent {
    }

    record Done() implements AgentEvent {
    }

    record Failed(String message) implements AgentEvent {
    }
}
