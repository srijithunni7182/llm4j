package io.github.llm4j.fairness;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BiasContextTest {

    @Test
    void testBuilder() {
        BiasContext context = BiasContext.builder()
                .sessionId("session-1")
                .userId("user-1")
                .taskType("summarization")
                .addContext("key", "value")
                .build();

        assertEquals("session-1", context.getSessionId());
        assertEquals("user-1", context.getUserId());
        assertEquals("summarization", context.getTaskType());
        assertEquals("value", context.getAdditionalContext().get("key"));
    }

    @Test
    void testEmptyContext() {
        BiasContext context = BiasContext.empty();
        assertNull(context.getSessionId());
        assertNotNull(context.getAdditionalContext());
        assertTrue(context.getAdditionalContext().isEmpty());
    }
}
