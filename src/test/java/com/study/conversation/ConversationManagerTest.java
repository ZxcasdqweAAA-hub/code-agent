package com.study.conversation;

import org.junit.jupiter.api.Test;

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
}
