package io.github.llm4j.a2a;

import java.util.List;
import java.util.Objects;

/**
 * One turn of an exchange.
 *
 * @param messageId an id made by the sender
 * @param role who is speaking
 * @param parts the content; at least one
 * @param contextId the conversation this belongs to, or null
 * @param taskId the task this answers, or null
 * @param referenceTaskIds tasks this refers to; never null
 */
public record A2AMessage(
        String messageId,
        Role role,
        List<Part> parts,
        String contextId,
        String taskId,
        List<String> referenceTaskIds) {

    /** Who is speaking. */
    public enum Role {
        /** The calling side. */
        USER,
        /** The agent side. */
        AGENT
    }

    /** Checks the id, the role and that there is at least one part; copies the lists. */
    public A2AMessage {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(parts, "parts");
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("a message needs at least one part");
        }
        parts = List.copyOf(parts);
        referenceTaskIds = referenceTaskIds == null ? List.of() : List.copyOf(referenceTaskIds);
    }

    /** A message from the caller with no context or task. */
    public static A2AMessage user(String messageId, List<Part> parts) {
        return new A2AMessage(messageId, Role.USER, parts, null, null, List.of());
    }

    /** A message from an agent with no context or task. */
    public static A2AMessage agent(String messageId, List<Part> parts) {
        return new A2AMessage(messageId, Role.AGENT, parts, null, null, List.of());
    }

    /** This message addressed to a task and context. */
    public A2AMessage forTask(String taskId, String contextId) {
        return new A2AMessage(messageId, role, parts, contextId, taskId, referenceTaskIds);
    }

    /** The text parts joined with a blank line. */
    public String text() {
        StringBuilder out = new StringBuilder();
        for (Part part : parts) {
            if (part instanceof Part.Text t) {
                if (out.length() > 0) {
                    out.append("\n\n");
                }
                out.append(t.text());
            }
        }
        return out.toString();
    }
}
