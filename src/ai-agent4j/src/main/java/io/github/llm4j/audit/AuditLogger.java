package io.github.llm4j.audit;

import java.time.Instant;
import java.util.Map;

/**
 * Interface for audit logging to support xAI compliance. Provides structured logging of agent
 * decisions, tool executions, and prompt usage.
 *
 * <p>Implementations should be thread-safe and handle errors gracefully.
 */
public interface AuditLogger {

    /**
     * Logs an agent decision event.
     *
     * @param event the audit event containing session, user, and result information
     */
    void logAgentDecision(AuditEvent event);

    /**
     * Logs a tool execution event.
     *
     * @param sessionId the session identifier
     * @param toolName the name of the tool executed
     * @param input the input provided to the tool
     * @param output the output returned by the tool
     * @param timestamp when the execution occurred
     */
    void logToolExecution(
            String sessionId, String toolName, String input, String output, Instant timestamp);

    /**
     * Logs prompt usage for traceability.
     *
     * @param sessionId the session identifier
     * @param promptId the prompt identifier
     * @param version the prompt version used
     * @param timestamp when the prompt was used
     */
    void logPromptUsage(String sessionId, String promptId, String version, Instant timestamp);

    /**
     * Logs a general conversation event.
     *
     * @param sessionId the session identifier
     * @param userId the user identifier (can be null)
     * @param eventType the type of event (e.g., "PII_DETECTED", "LOW_CONFIDENCE")
     * @param metadata additional event metadata
     */
    void logConversationEvent(
            String sessionId, String userId, String eventType, Map<String, Object> metadata);
}
