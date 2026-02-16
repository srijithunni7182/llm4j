package io.github.llm4j.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NoOpAuditLoggerTest {

    @Test
    void testLogAgentDecision_noSideEffects() {
        NoOpAuditLogger logger = new NoOpAuditLogger();
        AuditEvent event = AuditEvent.builder().sessionId("test-session").build();

        // Should not throw any exception
        assertDoesNotThrow(() -> logger.logAgentDecision(event));
    }

    @Test
    void testLogToolExecution_noSideEffects() {
        NoOpAuditLogger logger = new NoOpAuditLogger();

        assertDoesNotThrow(
                () -> logger.logToolExecution("session", "tool", "input", "output", Instant.now()));
    }

    @Test
    void testLogPromptUsage_noSideEffects() {
        NoOpAuditLogger logger = new NoOpAuditLogger();

        assertDoesNotThrow(
                () -> logger.logPromptUsage("session", "prompt-id", "v1", Instant.now()));
    }

    @Test
    void testLogConversationEvent_noSideEffects() {
        NoOpAuditLogger logger = new NoOpAuditLogger();

        assertDoesNotThrow(
                () ->
                        logger.logConversationEvent(
                                "session", "user", "TEST_EVENT", Map.of("key", "value")));
    }

    @Test
    void testPerformance_noOverhead() {
        NoOpAuditLogger logger = new NoOpAuditLogger();
        AuditEvent event = AuditEvent.builder().sessionId("test-session").build();

        long start = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            logger.logAgentDecision(event);
        }
        long duration = System.nanoTime() - start;

        // 10,000 calls should take less than 10ms (very generous threshold)
        assertTrue(
                duration < 10_000_000,
                "NoOpAuditLogger should have near-zero overhead, took: "
                        + (duration / 1_000_000)
                        + "ms");
    }
}
