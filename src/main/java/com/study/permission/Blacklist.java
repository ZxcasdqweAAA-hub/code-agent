package com.study.permission;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Blacklist {
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)(^|[;&|])\\s*rm\\s+(-[^\\s]*[rf][^\\s]*|-r\\s+-f|-f\\s+-r)\\s+(/|\\*|~|[a-z]:\\\\)"),
            Pattern.compile("(?i)(^|[;&|])\\s*del\\s+(/s|/q).*"),
            Pattern.compile("(?i)(^|[;&|])\\s*rmdir\\s+(/s|/q).*"),
            Pattern.compile("(?i)(^|[;&|])\\s*format\\b.*"),
            Pattern.compile("(?i)(^|[;&|])\\s*mkfs\\b.*"),
            Pattern.compile("(?i)(^|[;&|])\\s*shutdown\\b.*"),
            Pattern.compile("(?i)(^|[;&|])\\s*reboot\\b.*"),
            Pattern.compile("(?i)(^|[;&|])\\s*git\\s+reset\\s+--hard\\b.*"),
            Pattern.compile("(?i)(^|[;&|])\\s*git\\s+clean\\s+-[^\\s]*[fd][^\\s]*.*"));

    private Blacklist() {
    }

    public static boolean hits(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.toLowerCase(Locale.ROOT).replace('\\', '/');
        return PATTERNS.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }
}
