package com.study.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.conversation.Message;
import com.study.llm.LlmClient;
import com.study.llm.LlmStream;
import com.study.llm.LlmSystem;
import com.study.llm.Request;
import com.study.llm.StreamEvent;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public final class MemoryManager {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = Logger.getLogger(MemoryManager.class.getName());

    private final MemoryStore store;
    private final ReentrantLock updateLock = new ReentrantLock();
    private final Object progressLock = new Object();

    private volatile LlmClient client;
    private volatile Runnable onUpdated = () -> {
    };
    private int summarizedMessageCount;

    public MemoryManager(Path projectRoot) {
        Path root = projectRoot == null ? Path.of("") : projectRoot;
        Path memoryRoot = root.resolve(".code-agent").resolve("memory");
        this.store = new MemoryStore(memoryRoot);
    }

    public MemoryManager(MemoryStore store, LlmClient client) {
        this.store = store;
        this.client = client;
    }

    public void setClient(LlmClient client) {
        this.client = client;
    }

    public void onUpdated(Runnable onUpdated) {
        this.onUpdated = onUpdated == null ? () -> {
        } : onUpdated;
    }

    public String loadIndex() {
        return store.loadIndex();
    }

    public String loadPromptContext() {
        String index = loadIndex();
        if (index.isBlank()) {
            return "";
        }
        return """
                ## Automatic Memory

                Index path: `.code-agent/memory/MEMORY.md`

                Automatic memories are historical references. Current user instructions and verified evidence take
                precedence. `user_preference` is relatively reliable; other types should be evaluated using their
                `updated` time and verified against current facts. If an index summary is insufficient, use ReadFile
                on `.code-agent/memory/<filename>`.

                %s
                """.formatted(index).strip();
    }

    public List<String> listFiles() {
        return listMarkdown(store.dir());
    }

    public void markSummarizedThrough(int messageCount) {
        markSummarized(Math.max(0, messageCount));
    }

    public void resetSummarizedThrough(int messageCount) {
        synchronized (progressLock) {
            summarizedMessageCount = Math.max(0, messageCount);
        }
    }

    public void updateAsyncIfNeeded(List<Message> recentMessages, boolean periodic) {
        List<Message> snapshot = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        int start;
        synchronized (progressLock) {
            if (summarizedMessageCount > snapshot.size()) {
                summarizedMessageCount = 0;
            }
            start = summarizedMessageCount;
        }
        List<Message> unsummarized = start >= snapshot.size()
                ? List.of()
                : List.copyOf(snapshot.subList(start, snapshot.size()));
        if (unsummarized.isEmpty()) {
            return;
        }
        if (!periodic && !hasMemorySignal(unsummarized)) {
            return;
        }
        LlmClient current = client;
        if (current == null) {
            return;
        }
        int processedUntil = snapshot.size();
        Thread.ofVirtual().name("memory-update").start(() -> update(unsummarized, current, processedUntil));
    }

    public void updateAsyncIfSignaled(List<Message> recentMessages) {
        updateAsyncIfNeeded(recentMessages, false);
    }

    void update(List<Message> recentMessages, LlmClient current) {
        update(recentMessages, current, recentMessages == null ? 0 : recentMessages.size());
    }

    void update(List<Message> recentMessages, LlmClient current, int processedUntil) {
        if (!updateLock.tryLock()) {
            return;
        }
        try {
            String existing = loadIndex();
            String notes = store.loadNotes();
            Request request = new Request(
                    List.of(Message.user(MemoryPrompt.user(existing, notes, recentMessages))),
                    List.of(),
                    new LlmSystem(MemoryPrompt.system(), ""),
                    "");
            String reply = collectText(current.stream(request));
            List<UpdateAction> actions = parseActions(reply);
            boolean changed = apply(actions);
            markSummarized(processedUntil);
            if (changed) {
                onUpdated.run();
            }
        } catch (Exception e) {
            LOG.warning("memory update failed: " + e.getMessage());
        } finally {
            updateLock.unlock();
        }
    }

    private void markSummarized(int processedUntil) {
        synchronized (progressLock) {
            summarizedMessageCount = Math.max(summarizedMessageCount, processedUntil);
        }
    }

    private List<String> listMarkdown(Path dir) {
        try {
            if (!Files.isDirectory(dir)) {
                return List.of();
            }
            try (var stream = Files.list(dir)) {
                return stream
                        .filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".md"))
                        .sorted()
                        .toList();
            }
        } catch (Exception e) {
            LOG.warning("memory list files failed: " + e.getMessage());
            return List.of();
        }
    }

    private String collectText(LlmStream stream) throws InterruptedException {
        try (stream) {
            StringBuilder out = new StringBuilder();
            while (true) {
                StreamEvent event = stream.events().take();
                switch (event) {
                    case StreamEvent.TextDelta delta -> out.append(delta.text());
                    case StreamEvent.StreamEnd end -> {
                        return out.toString();
                    }
                    case StreamEvent.Error error -> throw new IllegalStateException(error.message());
                    case StreamEvent.Cancelled cancelled -> throw new IllegalStateException(cancelled.message());
                    default -> {
                    }
                }
            }
        }
    }

    private List<UpdateAction> parseActions(String reply) throws Exception {
        String json = stripCodeFence(reply == null ? "" : reply.strip());
        if (json.isBlank()) {
            return List.of();
        }
        return JSON.readValue(json, new TypeReference<>() {
        });
    }

    private String stripCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        String stripped = text.replaceFirst("^```(?:json)?\\s*", "");
        return stripped.replaceFirst("\\s*```$", "").strip();
    }

    private boolean apply(List<UpdateAction> actions) throws Exception {
        if (actions == null || actions.isEmpty()) {
            return false;
        }
        store.apply(actions);
        return true;
    }

    public boolean hasMemorySignal(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        return messages.stream()
                .filter(message -> "user".equals(message.role()))
                .map(Message::content)
                .anyMatch(MemoryManager::hasMemorySignal);
    }

    public static boolean hasMemorySignal(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("记住")
                || lower.contains("记忆")
                || lower.contains("别忘")
                || lower.contains("忘记")
                || lower.contains("忘掉")
                || lower.contains("纠正")
                || lower.contains("记错")
                || lower.contains("说错")
                || lower.contains("remember")
                || lower.contains("forget")
                || lower.contains("correct my memory")
                || lower.contains("memo");
    }
}
