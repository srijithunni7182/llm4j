package io.github.chatbot;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.llm4j.model.Message;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages saving and loading conversation histories to/from disk.
 */
public class ConversationPersistence {

    private static final String CONVERSATIONS_DIR = System.getProperty("user.home") + "/.llm4j/conversations";
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Saves a conversation to disk.
     */
    public static void saveConversation(String name, ConversationHistory history, String systemPrompt)
            throws IOException {
        ensureConversationsDirectory();

        String fileName = sanitizeFileName(name) + ".json";
        Path filePath = Paths.get(CONVERSATIONS_DIR, fileName);

        SavedConversation saved = new SavedConversation(
                name,
                systemPrompt,
                history.getMessages(),
                LocalDateTime.now());

        objectMapper.writeValue(filePath.toFile(), saved);
    }

    /**
     * Loads a conversation from disk.
     */
    public static LoadedConversation loadConversation(String name) throws IOException {
        String fileName = sanitizeFileName(name) + ".json";
        Path filePath = Paths.get(CONVERSATIONS_DIR, fileName);

        if (!Files.exists(filePath)) {
            throw new IOException("Conversation '" + name + "' not found");
        }

        SavedConversation saved = objectMapper.readValue(filePath.toFile(), SavedConversation.class);

        ConversationHistory history = new ConversationHistory();
        for (Message msg : saved.messages) {
            history.addMessage(msg);
        }

        return new LoadedConversation(history, saved.systemPrompt);
    }

    /**
     * Lists all saved conversations.
     */
    public static List<ConversationInfo> listConversations() throws IOException {
        ensureConversationsDirectory();

        Path dir = Paths.get(CONVERSATIONS_DIR);

        try (Stream<Path> paths = Files.list(dir)) {
            return paths
                    .filter(path -> path.toString().endsWith(".json"))
                    .map(path -> {
                        try {
                            SavedConversation saved = objectMapper.readValue(path.toFile(), SavedConversation.class);
                            return new ConversationInfo(
                                    saved.name,
                                    saved.messages.size(),
                                    saved.savedAt);
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(info -> info != null)
                    .sorted((a, b) -> b.savedAt.compareTo(a.savedAt)) // Most recent first
                    .collect(Collectors.toList());
        }
    }

    /**
     * Deletes a saved conversation.
     */
    public static boolean deleteConversation(String name) throws IOException {
        String fileName = sanitizeFileName(name) + ".json";
        Path filePath = Paths.get(CONVERSATIONS_DIR, fileName);

        if (!Files.exists(filePath)) {
            return false;
        }

        Files.delete(filePath);
        return true;
    }

    private static void ensureConversationsDirectory() throws IOException {
        Path dir = Paths.get(CONVERSATIONS_DIR);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    private static String sanitizeFileName(String name) {
        // Remove or replace characters that are invalid in file names
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // Data classes for serialization

    private static class SavedConversation {
        @JsonProperty
        public String name;

        @JsonProperty
        public String systemPrompt;

        @JsonProperty
        public List<Message> messages;

        @JsonProperty
        public LocalDateTime savedAt;

        // For Jackson
        public SavedConversation() {
        }

        public SavedConversation(String name, String systemPrompt, List<Message> messages, LocalDateTime savedAt) {
            this.name = name;
            this.systemPrompt = systemPrompt;
            this.messages = new ArrayList<>(messages);
            this.savedAt = savedAt;
        }
    }

    /**
     * Represents a loaded conversation with its system prompt.
     */
    public static class LoadedConversation {
        private final ConversationHistory history;
        private final String systemPrompt;

        public LoadedConversation(ConversationHistory history, String systemPrompt) {
            this.history = history;
            this.systemPrompt = systemPrompt;
        }

        public ConversationHistory getHistory() {
            return history;
        }

        public String getSystemPrompt() {
            return systemPrompt;
        }
    }

    /**
     * Information about a saved conversation.
     */
    public static class ConversationInfo {
        private final String name;
        private final int messageCount;
        private final LocalDateTime savedAt;

        public ConversationInfo(String name, int messageCount, LocalDateTime savedAt) {
            this.name = name;
            this.messageCount = messageCount;
            this.savedAt = savedAt;
        }

        public String getName() {
            return name;
        }

        public int getMessageCount() {
            return messageCount;
        }

        public LocalDateTime getSavedAt() {
            return savedAt;
        }

        public String getFormattedDate() {
            return savedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
    }
}
