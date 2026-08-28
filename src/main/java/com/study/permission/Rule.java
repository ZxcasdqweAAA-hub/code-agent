package com.study.permission;

import java.util.Optional;
import java.util.regex.Pattern;

public record Rule(String pattern, Decision decision) {
    public Rule {
        if (pattern == null) {
            throw new IllegalArgumentException("rule pattern cannot be null");
        }
        if (decision != Decision.ALLOW && decision != Decision.DENY) {
            throw new IllegalArgumentException("rule decision must be ALLOW or DENY");
        }
    }

    public static Optional<Rule> parse(String text, Decision decision) {
        if (text == null || text.isBlank() || (decision != Decision.ALLOW && decision != Decision.DENY)) {
            return Optional.empty();
        }
        String value = text.strip();
        int open = value.indexOf('(');
        if (open <= 0 || !value.endsWith(")")) {
            return Optional.empty();
        }
        String tool = value.substring(0, open).strip();
        String pattern = value.substring(open + 1, value.length() - 1);
        if (!"Bash".equalsIgnoreCase(tool) || pattern.isBlank()) {
            return Optional.empty();
        }
        try {
            Rule rule = new Rule(pattern, decision);
            rule.regex();
            return Optional.of(rule);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public static Rule exact(String command, Decision decision) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command cannot be blank");
        }
        return new Rule(escapeLiteral(command), decision);
    }

    public boolean matches(String command) {
        return command != null && regex().matcher(command).matches();
    }

    public String render() {
        return "Bash(" + pattern + ")";
    }

    private Pattern regex() {
        StringBuilder out = new StringBuilder("^");
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char current = pattern.charAt(i);
            if (current == '\\') {
                if (i + 1 < pattern.length()) {
                    char next = pattern.charAt(i + 1);
                    if (next == '*' || next == '\\') {
                        literal.append(next);
                        i++;
                        continue;
                    }
                }
                literal.append(current);
                continue;
            }
            if (current == '*') {
                appendQuoted(out, literal);
                out.append(".*");
            } else {
                literal.append(current);
            }
        }
        appendQuoted(out, literal);
        out.append('$');
        return Pattern.compile(out.toString(), Pattern.DOTALL);
    }

    private static void appendQuoted(StringBuilder out, StringBuilder literal) {
        if (!literal.isEmpty()) {
            out.append(Pattern.quote(literal.toString()));
            literal.setLength(0);
        }
    }

    private static String escapeLiteral(String command) {
        StringBuilder out = new StringBuilder(command.length());
        for (int i = 0; i < command.length(); i++) {
            char current = command.charAt(i);
            if (current == '\\' || current == '*') {
                out.append('\\');
            }
            out.append(current);
        }
        return out.toString();
    }
}
