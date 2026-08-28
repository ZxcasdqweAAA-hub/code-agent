package com.study.permission;

import java.util.List;

public record Settings(String defaultMode, List<String> allow, List<String> deny) {
    public Settings {
        defaultMode = defaultMode == null ? "" : defaultMode;
        allow = allow == null ? List.of() : List.copyOf(allow);
        deny = deny == null ? List.of() : List.copyOf(deny);
    }

    public static Settings empty() {
        return new Settings("", List.of(), List.of());
    }
}
