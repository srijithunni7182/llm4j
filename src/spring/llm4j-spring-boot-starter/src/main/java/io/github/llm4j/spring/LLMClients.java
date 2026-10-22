package io.github.llm4j.spring;

import io.github.llm4j.LLMClient;
import io.github.llm4j.routing.ProviderTier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every provider client, by name, in the order declared. Only the primary one is an {@code
 * LLMClient} bean (decorated by the budget and the masking); the ones here are the providers' own,
 * for code that must talk to a specific model.
 */
public final class LLMClients {

    private final Map<String, LLMClient> clients;
    private final Map<String, ProviderTier> tiers;

    LLMClients(Map<String, LLMClient> clients, Map<String, ProviderTier> tiers) {
        this.clients = new LinkedHashMap<>(clients);
        this.tiers = Map.copyOf(tiers);
    }

    public List<String> names() {
        return new ArrayList<>(clients.keySet());
    }

    /**
     * @throws IllegalArgumentException naming the configured clients when {@code name} is not one
     */
    public LLMClient get(String name) {
        LLMClient c = clients.get(name);
        if (c == null)
            throw new IllegalArgumentException(
                    "no llm4j client named "
                            + name
                            + "; configured: "
                            + String.join(", ", clients.keySet()));
        return c;
    }

    /** The tier set for the client ({@code BALANCED} when none was). */
    public ProviderTier tier(String name) {
        return tiers.getOrDefault(name, ProviderTier.BALANCED);
    }
}
