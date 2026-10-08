package io.github.llm4j.routing;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import java.util.List;
import java.util.Map;

/**
 * Defines a strategy for routing requests across multiple LLM clients.
 */
public interface RoutingStrategy {

    /**
     * Selects an LLMClient from the available pool based on the request.
     *
     * @param request the upcoming LLM Request
     * @param clients the available clients mapped by their configured ProviderTier
     * @return the selected LLMClient
     * @throws io.github.llm4j.exception.LLMException if no suitable client can be found
     */
    LLMClient selectClient(LLMRequest request, Map<ProviderTier, List<LLMClient>> clients);

    /**
     * Called when a selected client fails to process the request.
     * The strategy can then mark the client as temporarily degraded or update its internal state.
     *
     * @param failedClient the client that failed
     * @param request the request that failed
     * @param e the exception that occurred
     */
    default void onFailure(LLMClient failedClient, LLMRequest request, Exception e) {
        // Default is no-op
    }
}
