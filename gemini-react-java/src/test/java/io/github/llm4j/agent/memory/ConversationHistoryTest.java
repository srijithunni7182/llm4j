package io.github.llm4j.agent.memory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationHistoryTest {

    @Test
    void testAddMessages() {
        ConversationHistory history = new ConversationHistory(10);
        history.addUserMessage("Hello");
        history.addAssistantMessage("Hi there");

        assertEquals(2, history.getMessages().size());
        assertEquals("User: Hello\nAssistant: Hi there\n", history.getFormattedHistory());
    }

    @Test
    void testHistoryLimit() {
        ConversationHistory history = new ConversationHistory(2);
        history.addUserMessage("1");
        history.addAssistantMessage("2");
        history.addUserMessage("3");

        assertEquals(2, history.getMessages().size());
        // Should keep last 2: Assistant: 2, User: 3
        assertEquals("Assistant: 2\nUser: 3\n", history.getFormattedHistory());
    }

    @Test
    void testClear() {
        ConversationHistory history = new ConversationHistory(10);
        history.addUserMessage("test");
        history.clear();
        assertTrue(history.getMessages().isEmpty());
    }
}
