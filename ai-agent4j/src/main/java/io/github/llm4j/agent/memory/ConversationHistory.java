package io.github.llm4j.agent.memory;

import io.github.llm4j.model.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Manages conversation history for the agent, supporting a sliding window of messages and optional
 * persistence.
 */
public class ConversationHistory {

    private final String sessionId;
    private final ConversationStore store;
    private final int maxHistorySize;

    // Local cache (or transient state if no store)
    private final List<Message> messages = new ArrayList<>();

    public ConversationHistory(int maxHistorySize) {
        this(UUID.randomUUID().toString(), new InMemoryConversationStore(), maxHistorySize);
    }

    public ConversationHistory(String sessionId, ConversationStore store, int maxHistorySize) {
        this.sessionId = sessionId;
        this.store = store;
        this.maxHistorySize = maxHistorySize;

        // Load initial history if available
        if (store != null) {
            this.messages.addAll(store.loadHistory(sessionId, maxHistorySize));
        }
    }

    public void addUserMessage(String content) {
        Message msg = Message.user(content);
        addMessage(msg);
    }

    public void addAssistantMessage(String content) {
        Message msg = Message.assistant(content);
        addMessage(msg);
    }

    public void addMessage(Message message) {
        messages.add(message);
        if (store != null) {
            store.saveMessage(sessionId, message);
        }
        trimHistory();
    }

    public void clear() {
        messages.clear();
        if (store != null) {
            store.clear(sessionId);
        }
    }

    public String getFormattedHistory() {
        if (messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            sb.append(msg.getRole().getValue()).append(": ").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }

    private void trimHistory() {
        // Keep only the last maxHistorySize messages
        while (messages.size() > maxHistorySize) {
            messages.remove(0);
        }
    }

    public List<Message> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    public String getSessionId() {
        return sessionId;
    }
}
