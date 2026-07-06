package com.study.tui.tea;

public final class Style {
    private final String prefix;

    private Style(String prefix) {
        this.prefix = prefix;
    }

    public static Style fg(ANSI256Color color) {
        return new Style("\u001B[38;5;" + color.value() + "m");
    }

    public static Style bold() {
        return new Style("\u001B[1m");
    }

    public static Style dim() {
        return new Style("\u001B[2m");
    }

    public String render(String text) {
        return prefix + text + "\u001B[0m";
    }
}
