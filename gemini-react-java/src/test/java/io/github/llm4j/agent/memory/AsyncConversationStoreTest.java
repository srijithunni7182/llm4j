package io.github.llm4j.agent.memory;

import io.github.llm4j.model.Message;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AsyncConversationStoreTest {

    @Test
    void testAsyncSave() throws InterruptedException {
        // Mock delegate
        AtomicBoolean wasCalled = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        ConversationStore mockStore = new InMemoryConversationStore() {
            @Override
            public void saveMessage(String sessionId, Message message) {
                try {
                    Thread.sleep(100); // Simulate slow I/O
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                super.saveMessage(sessionId, message);
                wasCalled.set(true);
                latch.countDown();
            }
        };

        AsyncConversationStore asyncStore = new AsyncConversationStore(mockStore);

        long start = System.currentTimeMillis();
        asyncStore.saveMessage("test-session", Message.user("Hello"));
        long duration = System.currentTimeMillis() - start;

        // Verify method returned immediately (well under the 100ms sleep)
        assertTrue(duration < 50, "saveMessage should return immediately");
        assertFalse(wasCalled.get(), "Delegate should not have been called yet");

        // Wait for async completion
        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertTrue(wasCalled.get());
        assertEquals(1, mockStore.loadHistory("test-session", 10).size());
    }
}
