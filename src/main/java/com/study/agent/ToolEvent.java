package com.study.agent;

public record ToolEvent(String id, String name, String argumentsPreview, Phase phase, String result, boolean error) {
}
