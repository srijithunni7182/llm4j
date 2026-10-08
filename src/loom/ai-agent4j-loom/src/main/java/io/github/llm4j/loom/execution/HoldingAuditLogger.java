package io.github.llm4j.loom.execution;

import io.github.llm4j.audit.AuditEvent;
import io.github.llm4j.audit.AuditLogger;
import java.time.Instant;
import java.util.Map;

/**
 * Passes audit entries to the real logger, except while an agent is proposing a decision at {@code watch}: its entries (which carry its answer) are
 * held and delivered once the person has answered, so the audit log cannot show a proposal before the verdict exists.
 */
final class HoldingAuditLogger implements AuditLogger {

    private final AuditLogger real;
    private final Decider decider;

    HoldingAuditLogger(AuditLogger real, Decider decider) {
        this.real = real;
        this.decider = decider;
    }

    @Override
    public void logAgentDecision(AuditEvent event) {
        if (!decider.hold(() -> real.logAgentDecision(event))) real.logAgentDecision(event);
    }

    @Override
    public void logToolExecution(String sessionId, String toolName, String input, String output, Instant timestamp) {
        if (!decider.hold(() -> real.logToolExecution(sessionId, toolName, input, output, timestamp))) real.logToolExecution(sessionId, toolName, input, output, timestamp);
    }

    @Override
    public void logPromptUsage(String sessionId, String promptId, String version, Instant timestamp) {
        if (!decider.hold(() -> real.logPromptUsage(sessionId, promptId, version, timestamp))) real.logPromptUsage(sessionId, promptId, version, timestamp);
    }

    @Override
    public void logConversationEvent(String sessionId, String userId, String eventType, Map<String, Object> metadata) {
        if (!decider.hold(() -> real.logConversationEvent(sessionId, userId, eventType, metadata))) real.logConversationEvent(sessionId, userId, eventType, metadata);
    }
}
