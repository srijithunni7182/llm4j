package io.github.llm4j.audit;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.AgentResult;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditEventTest {

    @Test
    void testBuilder_requiresSessionId() {
        assertThrows(NullPointerException.class, () -> AuditEvent.builder().build());
    }

    @Test
    void testBuilder_allowsNullUserId() {
        AuditEvent event = AuditEvent.builder().sessionId("session-123").build();

        assertNull(event.getUserId());
    }

    @Test
    void testBuilder_defaultsToCurrentTimestamp() {
        Instant before = Instant.now();
        AuditEvent event = AuditEvent.builder().sessionId("session-123").build();
        Instant after = Instant.now();

        assertNotNull(event.getTimestamp());
        assertTrue(event.getTimestamp().isAfter(before.minusSeconds(1)));
        assertTrue(event.getTimestamp().isBefore(after.plusSeconds(1)));
    }

    @Test
    void testBuilder_customTimestamp() {
        Instant customTime = Instant.parse("2024-01-01T12:00:00Z");
        AuditEvent event =
                AuditEvent.builder().sessionId("session-123").timestamp(customTime).build();

        assertEquals(customTime, event.getTimestamp());
    }

    @Test
    void testBuilder_allFields() {
        Instant timestamp = Instant.now();
        AgentResult result = AgentResult.builder().finalAnswer("Test answer").build();

        AuditEvent event =
                AuditEvent.builder()
                        .sessionId("session-123")
                        .userId("user-456")
                        .agentResult(result)
                        .timestamp(timestamp)
                        .addMetadata("key1", "value1")
                        .addMetadata("key2", 123)
                        .build();

        assertEquals("session-123", event.getSessionId());
        assertEquals("user-456", event.getUserId());
        assertEquals(result, event.getAgentResult());
        assertEquals(timestamp, event.getTimestamp());
        assertEquals("value1", event.getMetadata().get("key1"));
        assertEquals(123, event.getMetadata().get("key2"));
    }

    @Test
    void testMetadata_isDefensiveCopy() {
        AuditEvent event =
                AuditEvent.builder().sessionId("session-123").addMetadata("key", "value").build();

        Map<String, Object> metadata = event.getMetadata();
        metadata.put("newKey", "newValue");

        // Original should not be affected
        assertFalse(event.getMetadata().containsKey("newKey"));
    }

    @Test
    void testEquals_sameValues() {
        Instant timestamp = Instant.now();

        AuditEvent event1 =
                AuditEvent.builder()
                        .sessionId("session-123")
                        .userId("user-456")
                        .timestamp(timestamp)
                        .build();

        AuditEvent event2 =
                AuditEvent.builder()
                        .sessionId("session-123")
                        .userId("user-456")
                        .timestamp(timestamp)
                        .build();

        assertEquals(event1, event2);
    }

    @Test
    void testEquals_differentSessionId() {
        Instant timestamp = Instant.now();

        AuditEvent event1 =
                AuditEvent.builder().sessionId("session-123").timestamp(timestamp).build();

        AuditEvent event2 =
                AuditEvent.builder().sessionId("session-456").timestamp(timestamp).build();

        assertNotEquals(event1, event2);
    }

    @Test
    void testHashCode_consistent() {
        Instant timestamp = Instant.now();

        AuditEvent event =
                AuditEvent.builder()
                        .sessionId("session-123")
                        .userId("user-456")
                        .timestamp(timestamp)
                        .build();

        int hash1 = event.hashCode();
        int hash2 = event.hashCode();

        assertEquals(hash1, hash2);
    }

    @Test
    void testToString_readable() {
        AuditEvent event = AuditEvent.builder().sessionId("session-123").userId("user-456").build();

        String str = event.toString();
        assertTrue(str.contains("session-123"));
        assertTrue(str.contains("user-456"));
    }
}
