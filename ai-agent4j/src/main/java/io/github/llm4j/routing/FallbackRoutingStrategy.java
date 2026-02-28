package io.github.llm4j.routing;

import io.github.llm4j.LLMClient;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.model.LLMRequest;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A routing strategy that sequentially falls back to the next available tier if the prior one fails.
 * By default, it begins at the cheapest tier (FAST_CHEAP) and falls upward.
 */
public class FallbackRoutingStrategy implements RoutingStrategy {

    @Override
    public LLMClient selectClient(LLMRequest request, Map<ProviderTier, List<LLMClient>> clients) {
        if (clients.isEmpty()) {
            throw new LLMException("No LLM Clients configured for routing.");
        }
        
        // Sort tiers by level (cheapest/lowest first)
        TreeMap<ProviderTier, List<LLMClient>> sortedTiers = new TreeMap<>(
            (t1, t2) -> Integer.compare(t1.getLevel(), t2.getLevel())
        );
        sortedTiers.putAll(clients);

        // Pick the first client from the first available tier
        for (List<LLMClient> tierClients : sortedTiers.values()) {
            if (tierClients != null && !tierClients.isEmpty()) {
                return tierClients.get(0);
            }
        }

        throw new LLMException("Could not find an available LLMClient.");
    }

    @Override
    public void onFailure(LLMClient failedClient, LLMRequest request, Exception e) {
        // Fallback strategy could potentially demote the failed client temporarily here.
        // For simple fallback, it relies on RoutingLLMClient to catch the error and retry
        // with the remaining sub-list of clients.
    }
}
