package io.github.llm4j.agent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages conversation history for the agent, supporting a sliding window of
 * messages.
 */
public class ConversationHistory {

    private final List<Message> messages = new ArrayList<>();
    private final int maxHistorySize;

    public ConversationHistory(int maxHistorySize) {
        this.maxHistorySize = maxHistorySize;
    }

    public void addUserMessage(String content) {
        messages.add(new Message("User", content));
        trimHistory();
    }

    public void addAssistantMessage(String content) {
        messages.add(new Message("Assistant", content));
        trimHistory();
    }

    public void clear() {
        messages.clear();
    }

    public String getFormattedHistory() {
        if (messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            sb.append(msg.role).append(": ").append(msg.content).append("\n");
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

    public static class Message {
        public final String role;
        public final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}
