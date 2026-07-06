package com.study.tui.tea;

public record ANSI256Color(int value) {
    public ANSI256Color {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("ANSI 256 color must be between 0 and 255");
        }
    }
}
