package io.github.llm4j.util;

import io.github.llm4j.LLMClient;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility to generate concise summaries of conversations using an LLM. */
public class ConversationSummarizer {

    private static final Logger logger = LoggerFactory.getLogger(ConversationSummarizer.class);
    private static final String SUMMARY_PROMPT =
            "Summarize the following conversation in a single concise sentence (max 10 words). "
                    + "Focus on the main topic or user intent. Do not use prefixes like 'The user wants to'.";

    private final LLMClient llmClient;

    public ConversationSummarizer(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * Generates a summary for the given messages.
     *
     * @param messages the conversation history
     * @return a short summary string, or a default string if generation fails
     */
    public String summarize(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "New Conversation";
        }

        try {
            LLMRequest request =
                    LLMRequest.builder()
                            .addSystemMessage(SUMMARY_PROMPT)
                            .addUserMessage(formatHistory(messages))
                            .maxTokens(50)
                            .temperature(0.3)
                            .build();

            LLMResponse response = llmClient.chat(request);
            String summary = response.getContent().trim();

            // Cleanup quotes if present
            if (summary.startsWith("\"") && summary.endsWith("\"")) {
                summary = summary.substring(1, summary.length() - 1);
            }
            return summary;
        } catch (LLMException e) {
            logger.warn("Failed to generate summary", e);
            return "Conversation (" + messages.size() + " messages)";
        }
    }

    private String formatHistory(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        // Use only last few messages for summary to save tokens
        int start = Math.max(0, messages.size() - 6);
        for (int i = start; i < messages.size(); i++) {
            Message msg = messages.get(i);
            sb.append(msg.getRole().getValue()).append(": ").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }
}
