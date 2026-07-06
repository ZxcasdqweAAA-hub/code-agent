package com.study.tui.tea;

public record MouseMessage(int x, int y, String action) implements Message {
}
