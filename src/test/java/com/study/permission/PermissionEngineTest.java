package com.study.permission;

import com.study.llm.ToolCall;
import com.study.tool.Tool;
import com.study.tool.ToolExecutionResult;
import com.study.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void windowsBlacklistCoversDriveRootsCmdAliasesAndPowerShellDeletes() {
        List<String> dangerous = List.of(
                "rm -rf C:\\",
                "rm -fr D:/",
                "del /s /q C:\\temp\\*",
                "del /f /s /q C:\\temp\\*",
                "erase C:\\temp\\* /q",
                "rmdir /s /q C:\\temp",
                "rd C:\\temp /s /q",
                "format C:",
                "diskpart /s clean-disk.txt",
                "shutdown /s /t 0",
                "powershell -Command \"Remove-Item C:\\temp -Recurse -Force\"",
                "pwsh.exe -NoProfile -Command \"Remove-Item C:\\temp -Force\"",
                "echo safe && rd /s /q C:\\temp");

        for (String command : dangerous) {
            assertTrue(Blacklist.hits(command), () -> "expected blacklist hit: " + command);
        }
    }

    @Test
    void windowsBlacklistDoesNotBlockOrdinaryReadOnlyOrScopedCommands() {
        List<String> safe = List.of(
                "dir C:\\temp",
                "type C:\\temp\\file.txt",
                "powershell -Command \"Get-ChildItem C:\\temp\"",
                "powershell -Command \"Get-Content C:\\temp\\file.txt\"",
                "rm -rf C:\\project\\target",
                "del C:\\temp\\one.tmp",
                "rmdir C:\\temp\\empty");

        for (String command : safe) {
            org.junit.jupiter.api.Assertions.assertFalse(Blacklist.hits(command),
                    () -> "unexpected blacklist hit: " + command);
        }
    }

    @Test
    void blacklistCannotBeBypassedByModesOrAllowRules() throws Exception {
        Fixture fixture = fixture("""
                permissions:
                  allow:
                    - Bash(git reset --hard HEAD)
                """, "");
        Tool bash = fixture.registry.get("Bash").orElseThrow();
        ToolCall call = call("Bash", "{\"command\":\"git reset --hard HEAD\"}");

        for (Mode mode : Mode.values()) {
            PermissionResult result = fixture.engine.check(mode, call, bash);
            assertEquals(Decision.DENY, result.decision());
            assertTrue(result.reason().contains("黑名单"));
        }
    }

    @Test
    void outsidePathsAskExceptInBypassAndPlanWritesAlwaysDeny() throws Exception {
        Fixture fixture = fixture("", "");
        Path outside = tempDir.resolve("outside.txt");
        Tool read = fixture.registry.get("ReadFile").orElseThrow();
        Tool write = fixture.registry.get("WriteFile").orElseThrow();
        ToolCall readCall = call("ReadFile", jsonPath(outside));
        ToolCall writeCall = call("WriteFile", jsonPath(outside));

        assertEquals(Decision.ASK, fixture.engine.check(Mode.DEFAULT, readCall, read).decision());
        assertEquals(Decision.ASK, fixture.engine.check(Mode.ACCEPT_EDITS, writeCall, write).decision());
        assertEquals(Decision.ALLOW, fixture.engine.check(Mode.BYPASS_PERMISSIONS, readCall, read).decision());
        assertEquals(Decision.ALLOW, fixture.engine.check(Mode.BYPASS_PERMISSIONS, writeCall, write).decision());
        assertEquals(Decision.ASK, fixture.engine.check(Mode.PLAN, readCall, read).decision());
        assertEquals(Decision.DENY, fixture.engine.check(Mode.PLAN, writeCall, write).decision());
    }

    @Test
    void fileChecksUseTheSameExecutionRootAsTheTool() throws Exception {
        Fixture fixture = fixture("", "");
        Path worktree = Files.createDirectory(tempDir.resolve("active-worktree-" + System.nanoTime()));
        Path worktreeFile = Files.writeString(worktree.resolve("inside.txt"), "worktree");
        Path originalFile = Files.writeString(fixture.workspace.resolve("original.txt"), "original");
        Tool read = fixture.registry.get("ReadFile").orElseThrow();
        Tool write = fixture.registry.get("WriteFile").orElseThrow();

        assertEquals(Decision.ALLOW, fixture.engine.check(Mode.DEFAULT,
                call("ReadFile", jsonPath(worktreeFile)), read, worktree).decision());
        assertEquals(Decision.ALLOW, fixture.engine.check(Mode.DEFAULT,
                call("ReadFile", "{\"path\":\"inside.txt\"}"), read, worktree).decision());
        assertEquals(Decision.ALLOW, fixture.engine.check(Mode.ACCEPT_EDITS,
                call("WriteFile", "{\"path\":\"new.txt\",\"content\":\"ok\"}"), write, worktree).decision());

        String escapeToOriginal = "../" + fixture.workspace.getFileName() + "/" + originalFile.getFileName();
        assertEquals(Decision.ASK, fixture.engine.check(Mode.DEFAULT,
                call("ReadFile", "{\"path\":\"" + escapeToOriginal + "\"}"), read, worktree).decision());
        assertEquals(Decision.ASK, fixture.engine.check(Mode.ACCEPT_EDITS,
                call("WriteFile", "{\"path\":\"" + escapeToOriginal + "\",\"content\":\"no\"}"),
                write, worktree).decision());
    }

    @Test
    void appliesModeMatrixInsideWorkspace() throws Exception {
        Fixture fixture = fixture("", "");
        Path file = fixture.workspace.resolve("ok.txt");
        Files.writeString(file, "ok");
        Tool read = fixture.registry.get("ReadFile").orElseThrow();
        Tool write = fixture.registry.get("WriteFile").orElseThrow();
        Tool bash = fixture.registry.get("Bash").orElseThrow();
        ToolCall readCall = call("ReadFile", jsonPath(file));
        ToolCall writeCall = call("WriteFile", jsonPath(file));
        ToolCall bashCall = call("Bash", "{\"command\":\"git status\"}");

        assertEquals(Decision.ALLOW, fixture.engine.check(Mode.DEFAULT, readCall, read).decision());
        assertEquals(Decision.ASK, fixture.engine.check(Mode.DEFAULT, writeCall, write).decision());
        assertEquals(Decision.ASK, fixture.engine.check(Mode.DEFAULT, bashCall, bash).decision());
        assertEquals(Decision.ALLOW, fixture.engine.check(Mode.ACCEPT_EDITS, writeCall, write).decision());
        assertEquals(Decision.ASK, fixture.engine.check(Mode.ACCEPT_EDITS, bashCall, bash).decision());
        assertEquals(Decision.ALLOW, fixture.engine.check(Mode.BYPASS_PERMISSIONS, bashCall, bash).decision());
        assertEquals(Decision.DENY, fixture.engine.check(Mode.PLAN, writeCall, write).decision());
        assertEquals(Decision.DENY, fixture.engine.check(Mode.PLAN, bashCall, bash).decision());
    }

    @Test
    void projectRulesOverridePersonalAndDenyWinsWithinLayer() throws Exception {
        Fixture fixture = fixture("""
                permissions:
                  allow:
                    - Bash(git *)
                  deny:
                    - Bash(git push *)
                """, """
                permissions:
                  allow:
                    - Bash(git push *)
                  deny:
                    - Bash(git *)
                """);
        Tool bash = fixture.registry.get("Bash").orElseThrow();

        PermissionResult push = fixture.engine.check(Mode.DEFAULT,
                call("Bash", "{\"command\":\"git push origin main\"}"), bash);
        PermissionResult status = fixture.engine.check(Mode.DEFAULT,
                call("Bash", "{\"command\":\"git status\"}"), bash);

        assertEquals(Decision.DENY, push.decision());
        assertTrue(push.reason().contains("项目级"));
        assertEquals(Decision.ALLOW, status.decision());
        assertTrue(status.reason().contains("项目级"));
    }

    @Test
    void fallsBackToPersonalRulesWhenProjectDoesNotMatch() throws Exception {
        Fixture fixture = fixture("""
                permissions:
                  deny:
                    - Bash(npm *)
                """, """
                permissions:
                  allow:
                    - Bash(git status)
                """);
        Tool bash = fixture.registry.get("Bash").orElseThrow();

        PermissionResult result = fixture.engine.check(Mode.DEFAULT,
                call("Bash", "{\"command\":\"git status\"}"), bash);

        assertEquals(Decision.ALLOW, result.decision());
        assertTrue(result.reason().contains("个人级"));
    }

    @Test
    void choosesProjectThenPersonalThenDefaultStartMode() throws Exception {
        assertEquals(Mode.BYPASS_PERMISSIONS,
                fixture("defaultMode: bypassPermissions\n", "defaultMode: acceptEdits\n").engine.startMode());
        assertEquals(Mode.ACCEPT_EDITS,
                fixture("defaultMode: plan\n", "defaultMode: acceptEdits\n").engine.startMode());
        assertEquals(Mode.DEFAULT, fixture("", "").engine.startMode());
    }

    @Test
    void systemAndReadOnlyToolsAreAllowedButUnknownToolsUseFallback() throws Exception {
        Fixture fixture = fixture("", "");
        Tool system = fakeTool("system", false, true);
        Tool readOnly = fakeTool("ReadOnlyCustom", true, false);
        Tool other = fakeTool("MutatingCustom", false, false);

        assertEquals(Decision.ALLOW,
                fixture.engine.check(Mode.DEFAULT, call("system", "{}"), system).decision());
        assertEquals(Decision.ALLOW,
                fixture.engine.check(Mode.DEFAULT, call("ReadOnlyCustom", "{}"), readOnly).decision());
        assertEquals(Decision.ASK,
                fixture.engine.check(Mode.DEFAULT, call("MutatingCustom", "{}"), other).decision());
        assertEquals(Decision.ALLOW,
                fixture.engine.check(Mode.BYPASS_PERMISSIONS, call("MutatingCustom", "{}"), other).decision());
        assertEquals(Decision.DENY,
                fixture.engine.check(Mode.PLAN, call("MutatingCustom", "{}"), other).decision());
    }

    @Test
    void malformedArgumentsNeverSilentlyAllowFileOrBashInDefault() throws Exception {
        Fixture fixture = fixture("", "");
        Tool read = fixture.registry.get("ReadFile").orElseThrow();
        Tool bash = fixture.registry.get("Bash").orElseThrow();

        assertEquals(Decision.ASK,
                fixture.engine.check(Mode.DEFAULT, call("ReadFile", "not-json"), read).decision());
        assertEquals(Decision.ASK,
                fixture.engine.check(Mode.DEFAULT, call("Bash", "not-json"), bash).decision());
    }

    @Test
    void permanentAllowIsImmediatePersistentIdempotentAndPreservesFields() throws Exception {
        Fixture fixture = fixture("", """
                defaultMode: acceptEdits
                custom: keep-root
                permissions:
                  future: keep-permissions
                """);
        Tool bash = fixture.registry.get("Bash").orElseThrow();
        ToolCall exact = call("Bash", "{\"command\":\"echo *.txt\"}");
        ToolCall similar = call("Bash", "{\"command\":\"echo a.txt\"}");

        assertEquals(Decision.ASK, fixture.engine.check(Mode.DEFAULT, exact, bash).decision());
        fixture.engine.persistPersonalAllow(exact);
        fixture.engine.persistPersonalAllow(exact);

        assertEquals(Decision.ALLOW, fixture.engine.check(Mode.DEFAULT, exact, bash).decision());
        assertEquals(Decision.ASK, fixture.engine.check(Mode.DEFAULT, similar, bash).decision());
        PermissionEngine rebuilt = PermissionEngine.create(fixture.workspace, fixture.home);
        assertEquals(Decision.ALLOW, rebuilt.check(Mode.DEFAULT, exact, bash).decision());

        SettingsLoader.LoadedSettings loaded = SettingsLoader.load(fixture.home.resolve(".code-agent/settings.yaml"));
        assertEquals("acceptEdits", loaded.settings().defaultMode());
        assertEquals(1, loaded.settings().allow().size());
        assertEquals("keep-root", loaded.document().get("custom"));
        Map<?, ?> permissions = (Map<?, ?>) loaded.document().get("permissions");
        assertEquals("keep-permissions", permissions.get("future"));
    }

    @Test
    void projectDenyOverridesPersistedPersonalAllow() throws Exception {
        Fixture fixture = fixture("""
                permissions:
                  deny:
                    - Bash(echo safe)
                """, "");
        Tool bash = fixture.registry.get("Bash").orElseThrow();
        ToolCall call = call("Bash", "{\"command\":\"echo safe\"}");

        fixture.engine.persistPersonalAllow(call);
        PermissionResult result = PermissionEngine.create(fixture.workspace, fixture.home)
                .check(Mode.DEFAULT, call, bash);

        assertEquals(Decision.DENY, result.decision());
        assertTrue(result.reason().contains("项目级"));
    }

    @Test
    void refusesToPersistDangerousOrNonBashCalls() throws Exception {
        Fixture fixture = fixture("", "");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> fixture.engine.persistPersonalAllow(call("ReadFile", "{}")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> fixture.engine.persistPersonalAllow(
                        call("Bash", "{\"command\":\"git reset --hard HEAD\"}")));
    }

    private Fixture fixture(String projectYaml, String personalYaml) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace-" + System.nanoTime()));
        Path home = Files.createDirectory(tempDir.resolve("home-" + System.nanoTime()));
        writeSettings(workspace.resolve(".code-agent/settings.yaml"), projectYaml);
        writeSettings(home.resolve(".code-agent/settings.yaml"), personalYaml);
        return new Fixture(workspace, home, PermissionEngine.create(workspace, home), ToolRegistry.createDefault());
    }

    private void writeSettings(Path path, String yaml) throws Exception {
        if (yaml == null || yaml.isBlank()) {
            return;
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, yaml);
    }

    private ToolCall call(String name, String arguments) {
        return new ToolCall("1", name, arguments);
    }

    private String jsonPath(Path path) {
        return "{\"path\":\"" + path.toString().replace("\\", "\\\\") + "\"}";
    }

    private Tool fakeTool(String name, boolean readOnly, boolean system) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test";
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of("type", "object");
            }

            @Override
            public boolean readOnly() {
                return readOnly;
            }

            @Override
            public boolean isSystem() {
                return system;
            }

            @Override
            public ToolExecutionResult execute(Map<String, Object> args) {
                return ToolExecutionResult.ok("ok");
            }
        };
    }

    private record Fixture(Path workspace, Path home, PermissionEngine engine, ToolRegistry registry) {
    }
}
