package com.study.command;

import com.study.permission.Mode;

public final class BuiltinUi {
    private BuiltinUi() {
    }

    public static Handler exit() {
        return (cancelled, ui, args) -> ui.quit();
    }

    public static Handler plan() {
        return (cancelled, ui, args) -> {
            ui.enterPlanMode();
            ui.println("已切换到 PLAN 模式");
        };
    }

    public static Handler resume() {
        return (cancelled, ui, args) -> {
            if (!ui.idle()) {
                ui.error("请等待当前任务完成");
                return;
            }
            ui.resumeSession(args == null ? "" : args.strip());
        };
    }

    public static Handler clear() {
        return (cancelled, ui, args) -> {
            if (!ui.idle()) {
                ui.error("请等待当前任务完成");
                return;
            }
            ui.clearAndNewSession();
        };
    }
}
