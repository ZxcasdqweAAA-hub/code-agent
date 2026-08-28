package com.study.conversation;

import com.study.llm.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationManagerTest {
    @Test
    void keepsMessagesInOrder() {
        ConversationManager conversation = new ConversationManager();

        conversation.addUserMessage("hello");
        conversation.addAssistantMessage("hi");

        assertEquals(2, conversation.getMessages().size());
        assertEquals("user", conversation.getMessages().get(0).role());
        assertEquals("hello", conversation.getMessages().get(0).content());
        assertEquals("assistant", conversation.getMessages().get(1).role());
        assertEquals("hi", conversation.getMessages().get(1).content());
    }

    @Test
    void exposesImmutableSnapshot() {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("hello");

        assertThrows(UnsupportedOperationException.class,
                () -> conversation.getMessages().add(new Message("assistant", "nope")));
    }

    @Test
    void exposesLastRole() {
        ConversationManager conversation = new ConversationManager();

        assertTrue(conversation.lastRole().isEmpty());
        conversation.addUserMessage("hello");
        assertEquals("user", conversation.lastRole().orElseThrow());
        conversation.addAssistantMessage("hi");
        assertEquals("assistant", conversation.lastRole().orElseThrow());
    }

    @Test
    void replacesMessagesWithSnapshot() {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("old");

        conversation.replaceMessages(List.of(Message.user("new"), Message.assistant("ok")));

        assertEquals(2, conversation.getMessages().size());
        assertEquals("new", conversation.getMessages().get(0).content());
        assertEquals("ok", conversation.getMessages().get(1).content());
    }

    @Test
    void appendAndReplaceCallbacksRunBeforeMutation() {
        List<Message> appended = new ArrayList<>();
        List<List<Message>> replaced = new ArrayList<>();
        ConversationManager conversation = new ConversationManager(appended::add, replaced::add);

        conversation.addUserMessage("hello");
        conversation.replaceMessages(List.of(Message.user("summary"), Message.assistant("ok")));

        assertEquals(1, appended.size());
        assertEquals("hello", appended.get(0).content());
        assertEquals(1, replaced.size());
        assertEquals(2, replaced.get(0).size());
        assertEquals("summary", conversation.getMessages().get(0).content());
    }

    @Test
    void appendFailureDoesNotMutateMemory() {
        ConversationManager conversation = new ConversationManager(message -> {
            throw new ConversationPersistenceException("append failed", new java.io.IOException("disk"));
        }, null);

        assertThrows(ConversationPersistenceException.class, () -> conversation.addUserMessage("lost"));
        assertTrue(conversation.getMessages().isEmpty());
    }

    @Test
    void replaceFailureDoesNotMutateMemory() {
        ConversationManager conversation = new ConversationManager(null, messages -> {
            throw new ConversationPersistenceException("replace failed", new java.io.IOException("disk"));
        });
        conversation.copyFrom(List.of(Message.user("old")));

        assertThrows(ConversationPersistenceException.class,
                () -> conversation.replaceMessages(List.of(Message.user("new"))));
        assertEquals(List.of(Message.user("old")), conversation.getMessages());
    }

    @Test
    void toolResultsCanUseDifferentMemoryAndPersistedForms() {
        List<Message> appended = new ArrayList<>();
        ConversationManager conversation = new ConversationManager(appended::add, ignored -> { });
        ToolResult full = new ToolResult("call-1", "full body", false);
        ToolResult compact = new ToolResult("call-1", "compact reference", false);

        conversation.addToolResults(List.of(full), List.of(compact));

        assertEquals(full, conversation.getMessages().getFirst().toolResults().getFirst());
        assertEquals(compact, appended.getFirst().toolResults().getFirst());
    }

    @Test
    void memoryOnlyToolResultReplacementDoesNotInvokePersistenceCallbacks() {
        List<Message> appended = new ArrayList<>();
        List<List<Message>> replaced = new ArrayList<>();
        ConversationManager conversation = new ConversationManager(appended::add, replaced::add);
        ToolResult full = new ToolResult("call-1", "full body", false);
        ToolResult compact = new ToolResult("call-1", "compact reference", false);
        conversation.addToolResults(List.of(full));

        conversation.replaceToolResultsInMemory(java.util.Map.of("call-1", compact));

        assertEquals(compact, conversation.getMessages().getFirst().toolResults().getFirst());
        assertEquals(1, appended.size());
        assertTrue(replaced.isEmpty());
    }

    @Test
    void truncatesTailWithSinglePersistenceOperation() {
        List<ConversationTruncation> truncations = new ArrayList<>();
        List<List<Message>> replaced = new ArrayList<>();
        ConversationManager conversation = new ConversationManager(
                ignored -> { }, replaced::add, truncations::add);
        conversation.copyFrom(List.of(
                Message.user("stable"), Message.user("failed"), Message.assistant("partial")));

        conversation.truncateTo(1);

        assertEquals(List.of(Message.user("stable")), conversation.getMessages());
        assertEquals(1, truncations.size());
        assertEquals(3, truncations.getFirst().fromSize());
        assertEquals(1, truncations.getFirst().toSize());
        assertTrue(replaced.isEmpty());
    }

    @Test
    void truncationFailureDoesNotMutateMemory() {
        ConversationManager conversation = new ConversationManager(null, null, ignored -> {
            throw new ConversationPersistenceException("truncate failed", new java.io.IOException("disk"));
        });
        conversation.copyFrom(List.of(Message.user("stable"), Message.user("failed")));

        assertThrows(ConversationPersistenceException.class, () -> conversation.truncateTo(1));
        assertEquals(List.of(Message.user("stable"), Message.user("failed")), conversation.getMessages());
    }

    @Test
    void legacyTwoCallbackManagerFallsBackToSnapshotForTruncation() {
        List<List<Message>> replaced = new ArrayList<>();
        ConversationManager conversation = new ConversationManager(null, replaced::add);
        conversation.copyFrom(List.of(Message.user("stable"), Message.user("failed")));

        conversation.truncateTo(1);

        assertEquals(List.of(List.of(Message.user("stable"))), replaced);
        assertEquals(List.of(Message.user("stable")), conversation.getMessages());
    }
}
