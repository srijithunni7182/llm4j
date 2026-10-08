package io.github.llm4j.agent.memory;

import io.github.llm4j.model.Message;
import java.util.List;

/**
 * Interface for persistent conversation storage. Implementations can store messages in memory,
 * files, or databases.
 */
public interface ConversationStore {

    /**
     * Saves a single message to the store for a specific session.
     *
     * @param sessionId the unique session identifier
     * @param message the message to save
     */
    void saveMessage(String sessionId, Message message);

    /**
     * Loads the most recent messages for a session.
     *
     * @param sessionId the unique session identifier
     * @param limit the maximum number of messages to return
     * @return list of messages, ordered chronologically
     */
    List<Message> loadHistory(String sessionId, int limit);

    /**
     * Clears all messages for a specific session.
     *
     * @param sessionId the unique session identifier
     */
    void clear(String sessionId);

    /**
     * Deletes a session entirely.
     *
     * @param sessionId the unique session identifier
     */
    void deleteSession(String sessionId);

    /**
     * Lists all available sessions with their metadata.
     *
     * @return list of session metadata
     */
    List<ConversationMetadata> listSessions();

    /**
     * Updates the summary for a specific session.
     *
     * @param sessionId the unique session identifier
     * @param summary the new summary text
     */
    void updateSummary(String sessionId, String summary);

    /**
     * Retrieves metadata for a specific session.
     *
     * @param sessionId the unique session identifier
     * @return metadata, or null if session not found
     */
    ConversationMetadata getMetadata(String sessionId);
}
