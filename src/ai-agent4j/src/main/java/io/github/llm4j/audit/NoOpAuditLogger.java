package io.github.llm4j.audit;

import java.time.Instant;
import java.util.Map;

/**
 * No-operation audit logger that does nothing. Used as the default implementation to maintain
 * backward compatibility.
 *
 * <p>This implementation has zero overhead and can be safely used in production when audit logging
 * is not required.
 */
public class NoOpAuditLogger implements AuditLogger {

    @Override
    public void logAgentDecision(AuditEvent event) {
        // No-op
    }

    @Override
    public void logToolExecution(
            String sessionId, String toolName, String input, String output, Instant timestamp) {
        // No-op
    }

    @Override
    public void logPromptUsage(
            String sessionId, String promptId, String version, Instant timestamp) {
        // No-op
    }

    @Override
    public void logConversationEvent(
            String sessionId, String userId, String eventType, Map<String, Object> metadata) {
        // No-op
    }
}
