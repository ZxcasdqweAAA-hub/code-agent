package com.study.permission;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Blacklist {
    private static final String COMMAND_START = "(?i)(^|[;&|])\\s*";
    private static final List<Pattern> PATTERNS = List.of(
            // Commands are normalized to forward slashes before matching, including Windows drive roots.
            Pattern.compile(COMMAND_START + "rm\\s+"
                    + "(-[^\\s]*r[^\\s]*f[^\\s]*|-[^\\s]*f[^\\s]*r[^\\s]*|-r\\s+-f|-f\\s+-r)"
                    + "\\s+[\\\"']?(/|\\*|~|[a-z]:/)[\\\"']?(?=\\s|$)"),
            // CMD accepts switches in several positions; catch recursive/quiet destructive variants and aliases.
            Pattern.compile(COMMAND_START + "(?:del|erase)\\b(?=[^;&|]*\\s/(?:s|q)\\b)[^;&|]*"),
            Pattern.compile(COMMAND_START + "(?:rmdir|rd)\\b(?=[^;&|]*\\s/(?:s|q)\\b)[^;&|]*"),
            Pattern.compile(COMMAND_START + "format\\b.*"),
            Pattern.compile(COMMAND_START + "diskpart\\b.*"),
            Pattern.compile(COMMAND_START + "mkfs\\b.*"),
            Pattern.compile(COMMAND_START + "shutdown\\b.*"),
            Pattern.compile(COMMAND_START + "reboot\\b.*"),
            // Prevent CMD from using Windows PowerShell or PowerShell 7 to bypass destructive-delete checks.
            Pattern.compile(COMMAND_START
                    + "(?:powershell(?:\\.exe)?|pwsh(?:\\.exe)?)\\b"
                    + "(?=[^;&|]*\\bremove-item\\b)"
                    + "(?=[^;&|]*\\s-(?:recurse|force)\\b)[^;&|]*"),
            Pattern.compile(COMMAND_START + "git\\s+reset\\s+--hard\\b.*"),
            Pattern.compile(COMMAND_START + "git\\s+clean\\s+-[^\\s]*[fd][^\\s]*.*"));

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
