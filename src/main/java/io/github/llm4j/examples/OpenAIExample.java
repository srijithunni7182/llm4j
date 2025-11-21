package io.github.llm4j.examples;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.openai.OpenAIProvider;

/**
 * Example demonstrating basic usage with OpenAI's GPT models.
 */
public class OpenAIExample {

    public static void main(String[] args) {
        // Check for API key
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Please set OPENAI_API_KEY environment variable");
            System.exit(1);
        }

        // Configure the client
        LLMConfig config = LLMConfig.builder()
                .apiKey(apiKey)
                .defaultModel("gpt-3.5-turbo")
                .enableLogging(true)
                .build();

        // Create client with OpenAI provider
        LLMClient client = new DefaultLLMClient(new OpenAIProvider(config));

        System.out.println("=== Simple Question ===");
        simpleQuestion(client);

        System.out.println("\n=== Multi-turn Conversation ===");
        multiTurnConversation(client);

        System.out.println("\n=== With Parameters ===");
        withParameters(client);
    }

    private static void simpleQuestion(LLMClient client) {
        LLMRequest request = LLMRequest.builder()
                .addUserMessage("What is the capital of France?")
                .build();

        LLMResponse response = client.chat(request);
        System.out.println("Response: " + response.getContent());
        System.out.println("Tokens used: " + response.getTokenUsage().getTotalTokens());
    }

    private static void multiTurnConversation(LLMClient client) {
        LLMRequest request = LLMRequest.builder()
                .addSystemMessage("You are a helpful math tutor.")
                .addUserMessage("What is 15 * 23?")
                .addAssistantMessage("15 * 23 = 345")
                .addUserMessage("Now multiply that result by 2")
                .build();

        LLMResponse response = client.chat(request);
        System.out.println("Response: " + response.getContent());
    }

    private static void withParameters(LLMClient client) {
        LLMRequest request = LLMRequest.builder()
                .addUserMessage("Write a haiku about programming")
                .temperature(0.9) // More creative
                .maxTokens(100)
                .build();

        LLMResponse response = client.chat(request);
        System.out.println("Response: " + response.getContent());
        System.out.println("Finish reason: " + response.getFinishReason());
    }
}
