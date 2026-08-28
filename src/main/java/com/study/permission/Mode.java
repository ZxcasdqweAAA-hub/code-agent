package com.study.permission;

import java.util.Locale;
import java.util.Optional;

public enum Mode {
    DEFAULT("default", "default", true),
    ACCEPT_EDITS("acceptEdits", "acceptEdits", true),
    BYPASS_PERMISSIONS("bypassPermissions", "bypassPermissions", true),
    PLAN("plan", "plan", false);

    private final String wireName;
    private final String displayName;
    private final boolean configurable;

    Mode(String wireName, String displayName, boolean configurable) {
        this.wireName = wireName;
        this.displayName = displayName;
        this.configurable = configurable;
    }

    public String wireName() {
        return wireName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean configurable() {
        return configurable;
    }

    public static Optional<Mode> parseConfigurable(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "");
        return switch (normalized) {
            case "default" -> Optional.of(DEFAULT);
            case "acceptedits" -> Optional.of(ACCEPT_EDITS);
            case "bypasspermissions" -> Optional.of(BYPASS_PERMISSIONS);
            default -> Optional.empty();
        };
    }
}
