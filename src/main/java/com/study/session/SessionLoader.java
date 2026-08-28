package com.study.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.conversation.Message;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SessionLoader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PENDING_SNAPSHOTS = 8;
    private static final int MAX_SNAPSHOT_MESSAGES = 10_000;

    private SessionLoader() {
    }

    public static List<Message> load(Path sessionDir) throws IOException {
        return loadInternal(sessionDir).messages();
    }

    public static List<Message> loadForResume(Path sessionDir) throws IOException {
        Path file = sessionDir.resolve("conversation.jsonl");
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IOException("会话文件不存在或不可读");
        }
        LoadResult result = loadInternal(sessionDir);
        if (result.recognizedEntries() == 0) {
            throw new IOException("会话文件没有可识别的有效记录");
        }
        return result.messages();
    }

    private static LoadResult loadInternal(Path sessionDir) throws IOException {
        Path file = sessionDir.resolve("conversation.jsonl");
        List<Message> messages = new ArrayList<>();
        Map<String, List<Message>> pending = new LinkedHashMap<>();
        Set<String> appliedTruncations = new HashSet<>();
        int recognizedEntries = 0;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    Entry entry = JSON.readValue(line, Entry.class);
                    if ("compact".equals(entry.type())) {
                        recognizedEntries++;
                        messages.clear();
                        continue;
                    }
                    if ("snapshot".equals(entry.type())) {
                        if (addSnapshotEntry(pending, entry)) {
                            recognizedEntries++;
                        }
                        continue;
                    }
                    if ("snapshot_commit".equals(entry.type())) {
                        List<Message> committed = pending.remove(entry.snapshotId());
                        if (committed != null) {
                            recognizedEntries++;
                            messages.clear();
                            messages.addAll(committed);
                        }
                        continue;
                    }
                    if ("truncate".equals(entry.type())) {
                        if (applyTruncate(messages, entry, appliedTruncations)) {
                            recognizedEntries++;
                        }
                        continue;
                    }
                    if (entry.role() != null && entry.type() == null) {
                        recognizedEntries++;
                        messages.add(toMessage(entry));
                    }
                } catch (Exception ignored) {
                    // Bad lines are skipped so an interrupted append cannot break resume.
                }
            }
        }
        return new LoadResult(truncateOrphanedToolCalls(messages), recognizedEntries);
    }

    private static boolean applyTruncate(List<Message> messages, Entry entry, Set<String> appliedOperations) {
        String operationId = entry.operationId();
        Integer fromSize = entry.fromSize();
        Integer toSize = entry.toSize();
        if (operationId == null || operationId.isBlank()
                || fromSize == null || toSize == null
                || !appliedOperations.add(operationId)) {
            return false;
        }
        if (messages.size() != fromSize || toSize < 0 || toSize > fromSize) {
            return false;
        }
        messages.subList(toSize, messages.size()).clear();
        return true;
    }

    private static boolean addSnapshotEntry(Map<String, List<Message>> pending, Entry entry) {
        String id = entry.snapshotId();
        if (id == null || id.isBlank() || entry.role() == null) {
            return false;
        }
        if (!pending.containsKey(id) && pending.size() >= MAX_PENDING_SNAPSHOTS) {
            String oldest = pending.keySet().iterator().next();
            pending.remove(oldest);
        }
        List<Message> candidate = pending.computeIfAbsent(id, ignored -> new ArrayList<>());
        if (candidate.size() >= MAX_SNAPSHOT_MESSAGES) {
            pending.remove(id);
            return false;
        }
        candidate.add(toMessage(entry));
        return true;
    }

    private static Message toMessage(Entry entry) {
        return new Message(
                entry.role(),
                entry.content() == null ? "" : entry.content(),
                entry.toolCalls() == null ? List.of() : entry.toolCalls(),
                entry.toolResults() == null ? List.of() : entry.toolResults());
    }

    public static List<Message> truncateOrphanedToolCalls(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Message> copy = new ArrayList<>(messages);
        Message last = copy.get(copy.size() - 1);
        if ("assistant".equals(last.role()) && !last.toolCalls().isEmpty()) {
            copy.remove(copy.size() - 1);
        }
        return List.copyOf(copy);
    }

    private record LoadResult(List<Message> messages, int recognizedEntries) {
    }
}
