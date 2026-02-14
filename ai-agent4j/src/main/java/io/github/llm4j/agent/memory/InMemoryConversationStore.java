package io.github.llm4j.agent.memory;

import io.github.llm4j.model.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory implementation of ConversationStore.
 * Useful for testing or ephemeral sessions.
 */
public class InMemoryConversationStore implements ConversationStore {

    private final Map<String, StoredSession> store = new ConcurrentHashMap<>();

    @Override
    public void saveMessage(String sessionId, Message message) {
        store.compute(sessionId, (k, v) -> {
            if (v == null) {
                v = new StoredSession(
                        new ConversationMetadata(k, "New Conversation", java.time.Instant.now()),
                        Collections.synchronizedList(new ArrayList<>()));
            }
            v.messages.add(message);
            v.metadata = new ConversationMetadata(k, v.metadata.getSummary(), java.time.Instant.now());
            return v;
        });
    }

    @Override
    public List<Message> loadHistory(String sessionId, int limit) {
        StoredSession session = store.get(sessionId);
        if (session == null || session.messages.isEmpty()) {
            return new ArrayList<>();
        }

        synchronized (session.messages) {
            int size = session.messages.size();
            if (size <= limit) {
                return new ArrayList<>(session.messages);
            }
            return new ArrayList<>(session.messages.subList(size - limit, size));
        }
    }

    @Override
    public void updateSummary(String sessionId, String summary) {
        store.computeIfPresent(sessionId, (k, v) -> {
            v.metadata = new ConversationMetadata(k, summary, java.time.Instant.now());
            return v;
        });
    }

    @Override
    public ConversationMetadata getMetadata(String sessionId) {
        StoredSession session = store.get(sessionId);
        return session != null ? session.metadata : null;
    }

    @Override
    public void clear(String sessionId) {
        store.remove(sessionId);
    }

    @Override
    public void deleteSession(String sessionId) {
        store.remove(sessionId);
    }

    @Override
    public List<ConversationMetadata> listSessions() {
        return store.values().stream()
                .map(s -> s.metadata)
                .collect(java.util.stream.Collectors.toList());
    }

    private static class StoredSession {
        ConversationMetadata metadata;
        final List<Message> messages;

        StoredSession(ConversationMetadata metadata, List<Message> messages) {
            this.metadata = metadata;
            this.messages = messages;
        }
    }
}
