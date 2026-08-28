package com.study.session;

import com.study.conversation.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void strictLoadAcceptsValidRecordsAndDamagedTail() throws Exception {
        Files.writeString(tempDir.resolve("conversation.jsonl"), """
                {"role":"user","content":"hello","ts":1,"model":"test"}
                {"role":"assistant","content":"world","ts":2}
                {broken-tail
                """);

        assertEquals(List.of(Message.user("hello"), Message.assistant("world")),
                SessionLoader.loadForResume(tempDir));
    }

    @Test
    void strictLoadRejectsMissingEmptyAndCompletelyInvalidFiles() throws Exception {
        Path missing = tempDir.resolve("missing");
        Files.createDirectories(missing);
        assertThrows(IOException.class, () -> SessionLoader.loadForResume(missing));

        Path empty = tempDir.resolve("empty");
        Files.createDirectories(empty);
        Files.writeString(empty.resolve("conversation.jsonl"), "");
        assertThrows(IOException.class, () -> SessionLoader.loadForResume(empty));

        Path invalid = tempDir.resolve("invalid");
        Files.createDirectories(invalid);
        Files.writeString(invalid.resolve("conversation.jsonl"), "secret-token-not-json\n{}\n");
        IOException error = assertThrows(IOException.class, () -> SessionLoader.loadForResume(invalid));
        assertFalse(error.getMessage().contains("secret-token-not-json"));
    }

    @Test
    void strictLoadAcceptsCommittedSnapshot() throws Exception {
        Files.writeString(tempDir.resolve("conversation.jsonl"), """
                {"type":"snapshot","role":"user","content":"snapshot","ts":1,"snapshot_id":"s1"}
                {"type":"snapshot_commit","ts":2,"snapshot_id":"s1"}
                """);

        assertEquals(List.of(Message.user("snapshot")), SessionLoader.loadForResume(tempDir));
    }
}
