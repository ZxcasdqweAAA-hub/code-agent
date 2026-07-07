package com.study.permission;

public record PermissionResult(Decision decision, String reason) {
    public static PermissionResult allow(String reason) {
        return new PermissionResult(Decision.ALLOW, reason);
    }

    public static PermissionResult deny(String reason) {
        return new PermissionResult(Decision.DENY, reason);
    }

    public static PermissionResult ask(String reason) {
        return new PermissionResult(Decision.ASK, reason);
    }
}
