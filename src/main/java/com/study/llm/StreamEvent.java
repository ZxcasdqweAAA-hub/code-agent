package com.study.llm;

public sealed interface StreamEvent permits StreamEvent.TextDelta, StreamEvent.ThinkingDelta, StreamEvent.ToolCallDelta, StreamEvent.ToolCallComplete, StreamEvent.UsageEvent, StreamEvent.StreamEnd, StreamEvent.Error {
    record TextDelta(String text) implements StreamEvent {
    }

    record ThinkingDelta(String text) implements StreamEvent {
    }

    record ToolCallDelta(String id, String name, String argumentsDelta) implements StreamEvent {
    }

    record ToolCallComplete(String id, String name, String arguments) implements StreamEvent {
        public ToolCall toToolCall() {
            return new ToolCall(id, name, arguments);
        }
    }

    record UsageEvent(Usage usage) implements StreamEvent {
    }

    record StreamEnd(String stopReason, int inputTokens, int outputTokens) implements StreamEvent {
    }

    record Error(String message) implements StreamEvent {
    }
}
