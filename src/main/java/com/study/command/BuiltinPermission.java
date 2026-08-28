package com.study.command;

import com.study.permission.Mode;

public final class BuiltinPermission {
    private BuiltinPermission() {
    }

    public static Handler handle() {
        return (cancelled, ui, args) -> {
            String value = args == null ? "" : args.strip();
            if (value.isEmpty()) {
                ui.println("当前权限模式: " + ui.mode().displayName());
                ui.println("可用模式: default, acceptEdits, bypassPermissions");
                ui.println("用法: /permission <mode>");
                return;
            }
            Mode target = Mode.parseConfigurable(value).orElse(null);
            if (target == null) {
                ui.error("无效权限模式。可用值: default, acceptEdits, bypassPermissions");
                return;
            }
            if (!ui.setPermissionMode(target)) {
                ui.error("Plan 模式中不能切换权限，请先使用 /do");
                return;
            }
            ui.println("已切换权限模式: " + target.displayName());
        };
    }
}
