package com.study.tui;

public class MarkdownRenderer {
    public String render(String markdown, int width) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean inCode = false;
        for (String line : markdown.split("\\R", -1)) {
            if (line.stripLeading().startsWith("```")) {
                inCode = !inCode;
                out.append(Styles.DIM).append(line).append(Styles.RESET).append(System.lineSeparator());
            } else if (inCode) {
                out.append("  ").append(line).append(System.lineSeparator());
            } else {
                out.append(line
                        .replaceAll("\\*\\*([^*]+)\\*\\*", Styles.BOLD + "$1" + Styles.RESET)
                        .replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", Styles.BOLD + "$1" + Styles.RESET)
                ).append(System.lineSeparator());
            }
        }
        return out.toString().stripTrailing();
    }
}
