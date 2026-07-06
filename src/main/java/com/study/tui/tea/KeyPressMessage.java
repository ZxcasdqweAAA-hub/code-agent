package com.study.tui.tea;

public record KeyPressMessage(String key, char[] runes) implements Message {
}
