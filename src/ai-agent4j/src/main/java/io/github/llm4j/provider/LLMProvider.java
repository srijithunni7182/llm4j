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
     * reason and the token usage. Providers with native streaming override this; the default
     * answers with {@link #chat} and returns it as one text chunk plus the final chunk, so every
     * provider can be used the same way.
     */
    default Stream<LLMResponse> chatStream(LLMRequest request) {
        LLMResponse whole = chat(request);
        java.util.Map<String, Object> meta =
                whole.getMetadata() == null ? java.util.Map.of() : whole.getMetadata();
        LLMResponse.Builder last =
                LLMResponse.builder()
                        .content("")
                        .model(whole.getModel())
                        .finishReason(whole.getFinishReason())
                        .metadata(new java.util.HashMap<>(meta));
        if (whole.getTokenUsage() != null) last.tokenUsage(whole.getTokenUsage());
        return Stream.of(
                LLMResponse.builder().content(whole.getContent()).model(whole.getModel()).build(),
                last.build());
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

    /**
     * Whether {@link #chat} honours {@link LLMRequest#getTools()} and returns {@link
     * LLMResponse#getToolCalls()}.
     */
    default boolean supportsToolCalling() {
        return false;
    }

    /**
     * Whether this provider can search the web inside a call and return the sources, for a request
     * that carries {@link LLMRequest#getGrounding()}. The default is {@link
     * io.github.llm4j.grounding.GroundingSupport#NONE}: the client refuses a grounded request
     * before any network call. A provider that returns {@code NATIVE} must also say which settings
     * it honours in {@link #groundingOptions()} and fill {@link LLMResponse#getCitations()} and
     * {@link LLMResponse#getSearches()}.
     */
    default io.github.llm4j.grounding.GroundingSupport groundingSupport() {
        return io.github.llm4j.grounding.GroundingSupport.NONE;
    }

    /**
     * The settings of a {@link io.github.llm4j.grounding.Grounding} this provider can honour (the
     * others are refused by the client, or dropped when the grounding is lenient). Empty by
     * default.
     */
    default java.util.Set<io.github.llm4j.grounding.GroundingOption> groundingOptions() {
        return java.util.Set.of();
    }
}
