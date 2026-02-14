package io.github.llm4j.agent.memory;

import io.github.llm4j.model.Message;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConversationHistoryTest {

    @Test
    void testHistoryTruncation() {
        ConversationHistory history = new ConversationHistory(2);
        history.addUserMessage("1");
        history.addAssistantMessage("2");
        history.addUserMessage("3");

        assertEquals(2, history.getMessages().size());
        assertEquals("2", history.getMessages().get(0).getContent());
        assertEquals("3", history.getMessages().get(1).getContent());
    }

    @Test
    void testPersistenceIntegration() {
        String sessionId = UUID.randomUUID().toString();
        ConversationStore store = new InMemoryConversationStore();

        ConversationHistory history1 = new ConversationHistory(sessionId, store, 5);
        history1.addUserMessage("Hello");

        // Simulate new instance for same session
        ConversationHistory history2 = new ConversationHistory(sessionId, store, 5);
        assertEquals(1, history2.getMessages().size());
        assertEquals("Hello", history2.getMessages().get(0).getContent());
    }

    @Test
    void testStoreUpdate() {
        String sessionId = UUID.randomUUID().toString();
        ConversationStore store = new InMemoryConversationStore();
        ConversationHistory history = new ConversationHistory(sessionId, store, 5);

        history.addUserMessage("Test");

        List<Message> savedHelper = store.loadHistory(sessionId, 10);
        assertEquals(1, savedHelper.size());
        assertEquals("Test", savedHelper.get(0).getContent());
    }
}
