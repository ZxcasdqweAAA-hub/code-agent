package com.study.session;

import com.study.conversation.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SessionWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void resumeWriterFailsFastWhenLockedAndCanOpenAfterRelease() throws Exception {
        Path session = tempDir.resolve("20260827-120000-abcd");
        Files.createDirectories(session);
        try (SessionWriter owner = SessionWriter.create(session, "model")) {
            owner.append(Message.user("first"));
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    assertThrows(IOException.class, () -> SessionWriter.openForResume(session, "model")));
        }

        try (SessionWriter resumed = SessionWriter.openForResume(session, "model")) {
            resumed.append(Message.assistant("second"));
        }

        assertEquals(2, SessionLoader.loadForResume(session).size());
    }

    @Test
    void resumeWriterRejectsMissingDirectory() {
        assertThrows(IOException.class,
                () -> SessionWriter.openForResume(tempDir.resolve("missing"), "model"));
    }
}
