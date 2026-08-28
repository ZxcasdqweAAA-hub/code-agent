package com.study.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsValidSettingsAndPreservesUnknownFields() throws Exception {
        Path file = tempDir.resolve("settings.yaml");
        Files.writeString(file, """
                defaultMode: acceptEdits
                custom: keep-me
                permissions:
                  allow:
                    - Bash(git status)
                    - Bash(mvn *)
                  deny:
                    - Bash(git push *)
                  future: keep-too
                """);

        SettingsLoader.LoadedSettings loaded = SettingsLoader.load(file);

        assertEquals("acceptEdits", loaded.settings().defaultMode());
        assertEquals(2, loaded.settings().allow().size());
        assertEquals(1, loaded.settings().deny().size());
        assertEquals("keep-me", loaded.document().get("custom"));
        assertTrue(loaded.warnings().isEmpty());
    }

    @Test
    void missingFileLoadsAsEmptySettings() {
        SettingsLoader.LoadedSettings loaded = SettingsLoader.load(tempDir.resolve("missing.yaml"));

        assertEquals(Settings.empty(), loaded.settings());
        assertTrue(loaded.warnings().isEmpty());
    }

    @Test
    void invalidYamlAndRootTypeDegradeSafely() throws Exception {
        Path invalid = tempDir.resolve("invalid.yaml");
        Path listRoot = tempDir.resolve("list.yaml");
        Files.writeString(invalid, "permissions: [");
        Files.writeString(listRoot, "- one\n- two\n");

        assertEquals(Settings.empty(), SettingsLoader.load(invalid).settings());
        assertFalse(SettingsLoader.load(invalid).warnings().isEmpty());
        assertEquals(Settings.empty(), SettingsLoader.load(listRoot).settings());
        assertFalse(SettingsLoader.load(listRoot).warnings().isEmpty());
    }

    @Test
    void skipsInvalidModeAndNonBashRulesWithoutLeakingValues() throws Exception {
        String secret = "sk-test-secret-value";
        Path file = tempDir.resolve("settings.yaml");
        Files.writeString(file, """
                defaultMode: plan
                api_key: %s
                permissions:
                  allow:
                    - Read(.env)
                    - Bash(git status)
                  deny: not-a-list
                """.formatted(secret));

        SettingsLoader.LoadedSettings loaded = SettingsLoader.load(file);
        String warnings = String.join("\n", loaded.warnings());

        assertEquals("", loaded.settings().defaultMode());
        assertEquals(1, loaded.settings().allow().size());
        assertTrue(loaded.settings().deny().isEmpty());
        assertFalse(loaded.warnings().isEmpty());
        assertFalse(warnings.contains(secret));
    }
}
