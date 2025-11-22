package io.github.chatbot;

import io.github.llm4j.model.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages conversation history for the chatbot.
 */
public class ConversationHistory {

    private final List<Message> messages;
    private final int maxHistory;

    public ConversationHistory() {
        this(20); // Keep last 20 messages by default
    }

    public ConversationHistory(int maxHistory) {
        this.messages = new ArrayList<>();
        this.maxHistory = maxHistory;
    }

    public void addSystemMessage(String content) {
        messages.add(Message.system(content));
        trimHistory();
    }

    public void addUserMessage(String content) {
        messages.add(Message.user(content));
        trimHistory();
    }

    public void addAssistantMessage(String content) {
        messages.add(Message.assistant(content));
        trimHistory();
    }

    public List<Message> getMessages() {
        return new ArrayList<>(messages);
    }

    public void clear() {
        // Keep only system message if present
        if (!messages.isEmpty() && messages.get(0).getRole() == Message.Role.SYSTEM) {
            Message systemMsg = messages.get(0);
            messages.clear();
            messages.add(systemMsg);
        } else {
            messages.clear();
        }
    }

    public int size() {
        return messages.size();
    }

    private void trimHistory() {
        // Keep system message + last N messages
        if (messages.size() > maxHistory) {
            Message systemMsg = null;
            if (messages.get(0).getRole() == Message.Role.SYSTEM) {
                systemMsg = messages.remove(0);
            }

            // Remove oldest messages
            while (messages.size() > maxHistory - 1) {
                messages.remove(0);
            }

            // Add system message back at the beginning
            if (systemMsg != null) {
                messages.add(0, systemMsg);
            }
        }
    }
}
