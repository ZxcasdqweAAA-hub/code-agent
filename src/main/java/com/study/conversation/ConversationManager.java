package com.study.conversation;

import com.study.llm.ToolCall;
import com.study.llm.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public class ConversationManager {
    private final List<Message> messages = new ArrayList<>();
    private final Consumer<Message> onAppend;
    private final Consumer<List<Message>> onReplace;
    private final Consumer<ConversationTruncation> onTruncate;

    public ConversationManager() {
        this(null, null, null);
    }

    public ConversationManager(Consumer<Message> onAppend, Consumer<List<Message>> onReplace) {
        this(onAppend, onReplace, null);
    }

    public ConversationManager(Consumer<Message> onAppend, Consumer<List<Message>> onReplace,
                               Consumer<ConversationTruncation> onTruncate) {
        this.onAppend = onAppend;
        this.onReplace = onReplace;
        this.onTruncate = onTruncate;
    }

    public static ConversationManager fromMessages(List<Message> messages,
                                                   Consumer<Message> onAppend,
                                                   Consumer<List<Message>> onReplace) {
        ConversationManager manager = new ConversationManager(onAppend, onReplace);
        if (messages != null) {
            synchronized (manager) {
                manager.messages.addAll(List.copyOf(messages));
            }
        }
        return manager;
    }

    public void addUserMessage(String text) {
        append(Message.user(text));
    }

    public void addAssistantMessage(String text) {
        append(Message.assistant(text));
    }

    public void addAssistantWithToolCalls(String text, List<ToolCall> calls) {
        append(Message.assistantWithToolCalls(text, calls));
    }

    public void addToolResults(List<ToolResult> results) {
        append(Message.toolResults(results));
    }

    public void addToolResults(List<ToolResult> memoryResults, List<ToolResult> persistedResults) {
        append(Message.toolResults(memoryResults), Message.toolResults(persistedResults));
    }

    /**
     * Aligns the in-memory tool results with their already-persisted compact forms.
     * This deliberately bypasses persistence callbacks: conversation.jsonl received
     * the compact forms when the tool message was first appended.
     */
    public synchronized void replaceToolResultsInMemory(Map<String, ToolResult> replacements) {
        if (replacements == null || replacements.isEmpty()) {
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (!"tool".equals(message.role()) || message.toolResults().isEmpty()) {
                continue;
            }
            List<ToolResult> updated = new ArrayList<>(message.toolResults().size());
            boolean changed = false;
            for (ToolResult result : message.toolResults()) {
                ToolResult replacement = replacements.get(result.toolCallId());
                updated.add(replacement == null ? result : replacement);
                changed |= replacement != null;
            }
            if (changed) {
                messages.set(i, new Message(message.role(), message.content(), message.toolCalls(), updated));
            }
        }
    }

    public synchronized List<Message> getMessages() {
        return List.copyOf(messages);
    }

    public void replaceMessages(List<Message> newMessages) {
        List<Message> snapshot = newMessages == null ? List.of() : List.copyOf(newMessages);
        synchronized (this) {
            if (onReplace != null) {
                onReplace.accept(snapshot);
            }
            messages.clear();
            messages.addAll(snapshot);
        }
    }

    public synchronized void truncateTo(int targetSize) {
        if (targetSize < 0 || targetSize > messages.size()) {
            throw new IllegalArgumentException("无效的会话截断位置: " + targetSize);
        }
        if (targetSize == messages.size()) {
            return;
        }
        ConversationTruncation truncation = new ConversationTruncation(
                UUID.randomUUID().toString(), messages.size(), targetSize);
        if (onTruncate != null) {
            onTruncate.accept(truncation);
        } else if (onReplace != null) {
            onReplace.accept(List.copyOf(messages.subList(0, targetSize)));
        }
        messages.subList(targetSize, messages.size()).clear();
    }

    private synchronized void append(Message message) {
        append(message, message);
    }

    private synchronized void append(Message memoryMessage, Message persistedMessage) {
        if (onAppend != null) {
            onAppend.accept(persistedMessage);
        }
        messages.add(memoryMessage);
    }

    public synchronized int size() {
        return messages.size();
    }

    /*
     * Resume intentionally bypasses persistence callbacks because the messages
     * already came from the active session log.
     */
    public synchronized void copyFrom(List<Message> newMessages) {
        messages.clear();
        if (newMessages != null) {
            messages.addAll(List.copyOf(newMessages));
        }
    }

    public synchronized Optional<String> lastRole() {
        if (messages.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(messages.get(messages.size() - 1).role());
    }
}
