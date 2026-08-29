package com.study.worktree;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class SessionStore {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;

    public SessionStore(Path file) {
        this.file = file;
    }

    public WorktreeSession read() throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        String text = Files.readString(file).trim();
        if (text.isBlank() || "null".equals(text)) {
            return null;
        }
        return JSON.readValue(text, WorktreeSession.class);
    }

    public void write(WorktreeSession session) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        String json = session == null ? "null" : JSON.writerWithDefaultPrettyPrinter().writeValueAsString(session);
        Files.writeString(tmp, json);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
