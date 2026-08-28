package com.study.command;

import com.study.permission.Mode;
import com.study.prompt.Reminder;

public final class BuiltinPrompt {
    private BuiltinPrompt() {
    }

    public static Handler doRun() {
        return (cancelled, ui, args) -> {
            if (ui.mode() != Mode.PLAN) {
                ui.error("当前不在 Plan 模式，不能执行 /do");
                return;
            }
            ui.exitPlanMode();
            ui.injectAndSend("/do", Reminder.EXECUTE_DIRECTIVE);
        };
    }
}
