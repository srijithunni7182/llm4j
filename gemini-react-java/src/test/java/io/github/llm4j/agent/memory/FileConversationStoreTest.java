package io.github.llm4j.agent.memory;

import io.github.llm4j.model.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FileConversationStoreTest {

    private Path tempDir;
    private FileConversationStore store;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("llm4j-test-conversations");
        store = new FileConversationStore(tempDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (Stream<Path> walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
        }
    }

    @Test
    void testSaveAndLoadHistory() {
        String sessionId = UUID.randomUUID().toString();
        Message msg1 = Message.user("Hello");
        Message msg2 = Message.assistant("Hi there");

        store.saveMessage(sessionId, msg1);
        store.saveMessage(sessionId, msg2);

        List<Message> history = store.loadHistory(sessionId, 10);
        assertEquals(2, history.size());
        assertEquals(msg1, history.get(0));
        assertEquals(msg2, history.get(1));
    }

    @Test
    void testLoadDataLimit() {
        String sessionId = UUID.randomUUID().toString();
        for (int i = 0; i < 5; i++) {
            store.saveMessage(sessionId, Message.user("Msg " + i));
        }

        List<Message> history = store.loadHistory(sessionId, 3);
        assertEquals(3, history.size());
        assertEquals("Msg 2", history.get(0).getContent());
        assertEquals("Msg 4", history.get(2).getContent());
    }

    @Test
    void testPersistenceAcrossInstances() {
        String sessionId = UUID.randomUUID().toString();
        store.saveMessage(sessionId, Message.user("Persist me"));

        // New instance pointing to same dir
        FileConversationStore newStore = new FileConversationStore(tempDir);
        List<Message> history = newStore.loadHistory(sessionId, 10);

        assertEquals(1, history.size());
        assertEquals("Persist me", history.get(0).getContent());
    }

    @Test
    void testClearSession() {
        String sessionId = UUID.randomUUID().toString();
        store.saveMessage(sessionId, Message.user("Delete me"));

        store.clear(sessionId);
        List<Message> history = store.loadHistory(sessionId, 10);
        assertTrue(history.isEmpty());
    }

    @Test
    void testListSessions() {
        store.saveMessage("session1", Message.user("Hi"));
        store.saveMessage("session2", Message.user("Hello"));

        List<ConversationMetadata> sessions = store.listSessions();
        assertEquals(2, sessions.size());

        // Check filtering/mapping
        boolean found1 = sessions.stream().anyMatch(m -> m.getSessionId().equals("session1"));
        boolean found2 = sessions.stream().anyMatch(m -> m.getSessionId().equals("session2"));
        assertTrue(found1);
        assertTrue(found2);
    }

    @Test
    void testUpdateSummary() {
        String sessionId = UUID.randomUUID().toString();
        store.saveMessage(sessionId, Message.user("Hello")); // Creates session

        store.updateSummary(sessionId, "My Summary");

        ConversationMetadata meta = store.getMetadata(sessionId);
        assertNotNull(meta);
        assertEquals("My Summary", meta.getSummary());

        // Verify via list
        List<ConversationMetadata> list = store.listSessions();
        assertEquals("My Summary", list.get(0).getSummary());
    }
}
