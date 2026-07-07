package com.study.permission;

import com.study.llm.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void blocksDangerousBashCommands() {
        PermissionEngine engine = PermissionEngine.create(tempDir);

        PermissionResult result = engine.check(new ToolCall("1", "Bash", "{\"command\":\"git reset --hard HEAD\"}"));

        assertEquals(Decision.DENY, result.decision());
    }

    @Test
    void deniesPathsOutsideWorkspace() {
        PermissionEngine engine = PermissionEngine.create(tempDir);
        Path outside = tempDir.getParent().resolve("outside.txt");

        PermissionResult result = engine.check(new ToolCall("1", "ReadFile", "{\"path\":\"" + jsonPath(outside) + "\"}"));

        assertEquals(Decision.DENY, result.decision());
    }

    @Test
    void asksForWriteInsideWorkspace() {
        PermissionEngine engine = PermissionEngine.create(tempDir);
        Path file = tempDir.resolve("ok.txt");

        PermissionResult result = engine.check(new ToolCall("1", "WriteFile", "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"hi\"}"));

        assertEquals(Decision.ASK, result.decision());
    }

    private String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
