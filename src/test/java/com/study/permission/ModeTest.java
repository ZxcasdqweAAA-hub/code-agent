package com.study.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModeTest {
    @Test
    void parsesConfigurableNamesCaseInsensitively() {
        assertEquals(Mode.DEFAULT, Mode.parseConfigurable("DEFAULT").orElseThrow());
        assertEquals(Mode.ACCEPT_EDITS, Mode.parseConfigurable("acceptEdits").orElseThrow());
        assertEquals(Mode.ACCEPT_EDITS, Mode.parseConfigurable("ACCEPT_EDITS").orElseThrow());
        assertEquals(Mode.BYPASS_PERMISSIONS, Mode.parseConfigurable("BypassPermissions").orElseThrow());
    }

    @Test
    void rejectsPlanAndUnknownValues() {
        assertTrue(Mode.parseConfigurable("plan").isEmpty());
        assertTrue(Mode.parseConfigurable("unknown").isEmpty());
        assertTrue(Mode.parseConfigurable("").isEmpty());
        assertTrue(Mode.parseConfigurable(null).isEmpty());
    }

    @Test
    void exposesStableNamesAndConfigurability() {
        assertEquals("acceptEdits", Mode.ACCEPT_EDITS.wireName());
        assertEquals("bypassPermissions", Mode.BYPASS_PERMISSIONS.displayName());
        assertTrue(Mode.DEFAULT.configurable());
        assertFalse(Mode.PLAN.configurable());
    }
}
