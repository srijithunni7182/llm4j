package io.github.llm4j.provider;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.stream.Stream;

/**
 * Service Provider Interface (SPI) for implementing LLM provider integrations. Each provider
 * (OpenAI, Anthropic, Google, etc.) implements this interface to handle provider-specific API calls
 * and transformations.
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
    /**
     * Streams the answer: text chunks as they arrive, then one final chunk with no text, the finish
     * reason and the token usage. Providers with native streaming override this; the default answers
     * with {@link #chat} and returns it as one text chunk plus the final chunk, so every provider can
     * be used the same way.
     */
    default Stream<LLMResponse> chatStream(LLMRequest request) {
        LLMResponse whole = chat(request);
        java.util.Map<String, Object> meta = whole.getMetadata() == null ? java.util.Map.of() : whole.getMetadata();
        LLMResponse.Builder last = LLMResponse.builder().content("").model(whole.getModel())
                .finishReason(whole.getFinishReason()).metadata(new java.util.HashMap<>(meta));
        if (whole.getTokenUsage() != null) last.tokenUsage(whole.getTokenUsage());
        return Stream.of(LLMResponse.builder().content(whole.getContent()).model(whole.getModel()).build(), last.build());
    }

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
