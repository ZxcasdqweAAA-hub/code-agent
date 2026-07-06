package com.study.tool;

import java.nio.charset.StandardCharsets;

public final class Truncate {
    private Truncate() {
    }

    public static String byLinesAndBytes(String text, int maxLines, int maxBytes) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        String[] lines = text.split("\\R", -1);
        boolean truncated = false;
        for (int i = 0; i < lines.length; i++) {
            if (i >= maxLines || bytes(out) + bytes(lines[i]) > maxBytes) {
                truncated = true;
                break;
            }
            if (i > 0) {
                out.append(System.lineSeparator());
            }
            out.append(lines[i]);
        }
        if (truncated) {
            out.append(System.lineSeparator()).append("[truncated]");
        }
        return out.toString();
    }

    public static String chars(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, Math.max(0, maxChars)) + System.lineSeparator() + "[truncated]";
    }

    private static int bytes(CharSequence text) {
        return text.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
