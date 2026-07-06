package com.study.tui;

public record ChatMessage(String role, String content, boolean error) {
}
