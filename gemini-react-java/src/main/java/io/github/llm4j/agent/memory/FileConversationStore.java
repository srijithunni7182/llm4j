package io.github.llm4j.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.llm4j.model.Message;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * File-based implementation of ConversationStore.
 * Persists each session as a JSON file.
 */
public class FileConversationStore implements ConversationStore {

    private final Path storageDirectory;
    private final ObjectMapper objectMapper;

    public FileConversationStore(Path storageDirectory) {
        this.storageDirectory = storageDirectory;
        this.objectMapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT);

        // Ensure directory exists
        if (!Files.exists(storageDirectory)) {
            try {
                Files.createDirectories(storageDirectory);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create conversation storage directory", e);
            }
        }
    }

    @Override
    public synchronized void saveMessage(String sessionId, Message message) {
        try {
            StoredSession session = loadSession(sessionId);
            if (session == null) {
                // New session
                session = new StoredSession(
                        new ConversationMetadata(sessionId, "New Conversation", java.time.Instant.now()),
                        new ArrayList<>());
            }
            session.messages.add(message);
            // Update timestamp
            session.metadata = new ConversationMetadata(sessionId, session.metadata.getSummary(),
                    java.time.Instant.now());

            saveSession(sessionId, session);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save message for session " + sessionId, e);
        }
    }

    @Override
    public synchronized List<Message> loadHistory(String sessionId, int limit) {
        try {
            StoredSession session = loadSession(sessionId);
            if (session == null) {
                return new ArrayList<>();
            }
            List<Message> history = session.messages;
            int size = history.size();
            if (size <= limit) {
                return history;
            }
            return history.subList(size - limit, size);
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    @Override
    public synchronized void updateSummary(String sessionId, String summary) {
        try {
            StoredSession session = loadSession(sessionId);
            if (session != null) {
                session.metadata = new ConversationMetadata(sessionId, summary, java.time.Instant.now());
                saveSession(sessionId, session);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to update summary for session " + sessionId, e);
        }
    }

    @Override
    public synchronized ConversationMetadata getMetadata(String sessionId) {
        try {
            StoredSession session = loadSession(sessionId);
            return session != null ? session.metadata : null;
        } catch (IOException e) {
            throw new RuntimeException("Failed to get metadata for session " + sessionId, e);
        }
    }

    @Override
    public synchronized void clear(String sessionId) {
        try {
            Path file = getSessionFile(sessionId);
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to clear session " + sessionId, e);
        }
    }

    @Override
    public synchronized void deleteSession(String sessionId) {
        clear(sessionId);
    }

    @Override
    public List<ConversationMetadata> listSessions() {
        try (Stream<Path> files = Files.list(storageDirectory)) {
            return files
                    .filter(path -> path.toString().endsWith(".json"))
                    .map(path -> {
                        try {
                            // We only need metadata, but for now load full file
                            // Optimization: Could store metadata in separate file or header
                            StoredSession session = objectMapper.readValue(path.toFile(), StoredSession.class);
                            return session.metadata;
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(meta -> meta != null)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    private StoredSession loadSession(String sessionId) throws IOException {
        Path file = getSessionFile(sessionId);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(file.toFile(), StoredSession.class);
        } catch (Exception e) {
            // Fallback for migration: try reading as List<Message>
            try {
                List<Message> oldFormat = objectMapper.readValue(file.toFile(), new TypeReference<List<Message>>() {
                });
                ConversationMetadata meta = new ConversationMetadata(sessionId, "Migrated Conversation",
                        java.time.Instant.now());
                return new StoredSession(meta, oldFormat);
            } catch (Exception ignored) {
                throw e; // Rethrow original error if not old format
            }
        }
    }

    private void saveSession(String sessionId, StoredSession session) throws IOException {
        Path file = getSessionFile(sessionId);
        objectMapper.writeValue(file.toFile(), session);
    }

    private Path getSessionFile(String sessionId) {
        // Sanitize session ID to prevent path traversal
        String safeName = sessionId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return storageDirectory.resolve(safeName + ".json");
    }

    // Internal storage structure
    private static class StoredSession {
        public ConversationMetadata metadata;
        public List<Message> messages;

        // Jackson needs default constructor
        public StoredSession() {
        }

        public StoredSession(ConversationMetadata metadata, List<Message> messages) {
            this.metadata = metadata;
            this.messages = messages;
        }
    }
}
