package io.github.llm4j.provider;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;

import java.util.stream.Stream;

/**
 * Service Provider Interface (SPI) for implementing LLM provider integrations.
 * Each provider (OpenAI, Anthropic, Google, etc.) implements this interface
 * to handle provider-specific API calls and transformations.
 */
public interface LLMProvider {
    
    /**
     * Sends a chat request to the provider's API.
     *
     * @param request the standardized LLM request
     * @return the standardized LLM response
     */
    LLMResponse chat(LLMRequest request);
    
    /**
     * Sends a streaming chat request to the provider's API.
     *
     * @param request the standardized LLM request
     * @return a stream of response chunks
     */
    Stream<LLMResponse> chatStream(LLMRequest request);
    
    /**
     * Returns the name of this provider (e.g., "openai", "anthropic", "google").
     *
     * @return the provider name
     */
    String getProviderName();
    
    /**
     * Validates that the provider is properly configured.
     *
     * @throws io.github.llm4j.exception.LLMException if the provider is not properly configured
     */
    void validate();
}
