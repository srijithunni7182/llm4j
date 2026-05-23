package io.github.llm4j.loom.execution;

import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.provider.ollama.OllamaProvider;

import java.util.stream.Stream;

/**
 * A default implementation of LLMClientFactory that discovers providers based on model names
 * and environment variables.
 */
public class DefaultLLMClientFactory implements LLMClientFactory {

    @Override
    public LLMClient createClient(String modelName) {
        String modelLower = modelName.toLowerCase();
        String normalizedModel = modelLower.startsWith("ollama/") ? modelName.substring("ollama/".length()) : modelName;

        if (modelLower.contains("gemini")) {
            String apiKey = System.getenv("GEMINI_API_KEY");
            if (apiKey == null || apiKey.isEmpty()) {
                apiKey = System.getProperty("google.api.key");
            }
            
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalStateException("GEMINI_API_KEY environment variable is required for model: " + modelName);
            }

            LLMConfig config = LLMConfig.builder()
                    .apiKey(apiKey)
                    .defaultModel(normalizedModel)
                    .build();
            
            GoogleProvider provider = new GoogleProvider(config);
            return wrapProvider(provider);
        } 
        
        if (modelLower.startsWith("ollama/") || modelLower.contains("llama") || modelLower.contains("gemma") || modelLower.contains("mistral")) {
            String baseUrl = System.getenv("OLLAMA_BASE_URL");
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = "http://localhost:11434"; // Default Ollama port
            }

            LLMConfig config = LLMConfig.builder()
                    .baseUrl(baseUrl)
                    .defaultModel(normalizedModel)
                    .build();

            OllamaProvider provider = new OllamaProvider(config);
            return wrapProvider(provider);
        }

        throw new IllegalArgumentException("Unsupported model or provider not configured for: " + modelName +
                ". Supported patterns: 'gemini-*' for cloud and 'ollama/<model>' (or llama/gemma/mistral) for local.");
    }

    private LLMClient wrapProvider(final io.github.llm4j.provider.LLMProvider provider) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                return provider.chat(request);
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return provider.chatStream(request);
            }
        };
    }
}
