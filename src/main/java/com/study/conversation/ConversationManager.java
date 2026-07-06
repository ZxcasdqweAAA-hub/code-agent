package com.study.conversation;

import com.study.llm.ToolCall;
import com.study.llm.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ConversationManager {
    private final List<Message> messages = new ArrayList<>();

    public synchronized void addUserMessage(String text) {
        messages.add(Message.user(text));
    }

    public synchronized void addAssistantMessage(String text) {
        messages.add(Message.assistant(text));
    }

    public synchronized void addAssistantWithToolCalls(String text, List<ToolCall> calls) {
        messages.add(Message.assistantWithToolCalls(text, calls));
    }

    public synchronized void addToolResults(List<ToolResult> results) {
        messages.add(Message.toolResults(results));
    }

    public synchronized List<Message> getMessages() {
        return List.copyOf(messages);
    }

    public synchronized Optional<String> lastRole() {
        if (messages.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(messages.get(messages.size() - 1).role());
    }
}
