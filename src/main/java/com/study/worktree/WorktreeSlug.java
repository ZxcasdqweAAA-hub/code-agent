package com.study.worktree;

import java.util.regex.Pattern;

public final class WorktreeSlug {
    private static final int MAX_LENGTH = 64;
    private static final Pattern SEGMENT = Pattern.compile("^[a-zA-Z0-9._-]+$");

    private WorktreeSlug() {
    }

    public static void validate(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("worktree 名称不能为空");
        }
        if (name.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("worktree 名称不能超过 " + MAX_LENGTH + " 个字符");
        }
        if (name.startsWith("/") || name.endsWith("/") || name.contains("//")) {
            throw new IllegalArgumentException("worktree 名称不能以 / 开头或结尾，也不能包含连续 /");
        }
        for (String part : name.split("/")) {
            if (part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("worktree 名称不能包含 . 或 .. 段");
            }
            if (!SEGMENT.matcher(part).matches()) {
                throw new IllegalArgumentException("worktree 名称只允许字母、数字、点、下划线、短横线和 /");
            }
        }
    }

    public static String flatten(String name) {
        validate(name);
        return name.replace("/", "+");
    }
}
