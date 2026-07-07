package com.study.prompt;

public final class Reminder {
    public static final String PLAN_REMINDER_FULL = """
            You are in PLAN MODE. Use only read-only tools to investigate.
            Do not write files, edit files, or execute shell commands with side effects.
            Produce a clear step-by-step plan and wait for the user to approve execution with /do.
            """.strip();

    public static final String PLAN_REMINDER_CONCISE = """
            PLAN MODE: continue using only read-only tools and refine the plan. Wait for /do before making changes.
            """.strip();

    public static final String EXECUTE_DIRECTIVE = "Please execute the approved plan now.";

    private Reminder() {
    }

    public static String systemReminder(String body) {
        return "<system-reminder>" + java.lang.System.lineSeparator()
                + (body == null ? "" : body.strip()) + java.lang.System.lineSeparator()
                + "</system-reminder>";
    }

    public static String plan(boolean full) {
        return systemReminder(full ? PLAN_REMINDER_FULL : PLAN_REMINDER_CONCISE);
    }
}
