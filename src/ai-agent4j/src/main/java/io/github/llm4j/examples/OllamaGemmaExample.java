package io.github.llm4j.examples;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.ollama.OllamaProvider;

public class OllamaGemmaExample {

    public static void main(String[] args) {
        // Assume Ollama is running locally at http://localhost:11434
        // To run Gemma, first run `ollama run gemma` (or whatever specific tag you downloaded)
        // in your terminal.

        LLMConfig config = LLMConfig.builder()
                .defaultModel("gemma") 
                // No API key is required for local Ollama by default
                .build();

        OllamaProvider provider = new OllamaProvider(config);

        System.out.println("Initialized OllamaProvider.");
        System.out.println("Available models on your local Ollama server:");
        try {
            String[] models = provider.listModels();
            if (models == null || models.length == 0) {
                System.out.println("No models found. Please run `ollama pull gemma` in your terminal.");
                return;
            }
            for (String model : models) {
                System.out.println(" - " + model);
            }
        } catch (Exception e) {
            System.err.println("Could not connect to Ollama. Make sure the service is running (http://localhost:11434).");
            return;
        }

        LLMRequest request = LLMRequest.builder()
                .addSystemMessage("You are a helpful programming assistant prioritizing concise answers.")
                .addUserMessage("Write a Java Hello World program.")
                .temperature(0.7)
                .build();

        System.out.println("\nSending request to local Gemma model via Ollama...\n");

        LLMResponse response = provider.chat(request);

        System.out.println("Response:\n" + response.getContent());
        
        if (response.getTokenUsage() != null) {
            System.out.println("\nToken Usage: Prompt " + response.getTokenUsage().getPromptTokens() + 
                               ", Output " + response.getTokenUsage().getCompletionTokens() + 
                               ", Total " + response.getTokenUsage().getTotalTokens());
        }
    }
}
