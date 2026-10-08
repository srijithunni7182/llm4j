package io.github.llm4j.routing;

import io.github.llm4j.LLMClient;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.model.ComplexityHint;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.Message;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A sophisticated routing strategy that estimates the computational cost and complexity
 * required by a request and routes it to the most cost-effective tier capable of handling it.
 */
public class CostAwareRoutingStrategy implements RoutingStrategy {

    // Thresholds for heuristics
    private static final int DEFAULT_LONG_CONTEXT_CHARS = 40000; // Roughly 10k tokens
    private static final double DEFAULT_HIGH_TEMP = 0.8;

    @Override
    public LLMClient selectClient(LLMRequest request, Map<ProviderTier, List<LLMClient>> clients) {
        if (clients.isEmpty()) {
            throw new LLMException("No LLM Clients configured for routing.");
        }

        ProviderTier requiredTier = determineRequiredTier(request);

        // Sort tiers by level
        TreeMap<ProviderTier, List<LLMClient>> sortedTiers = new TreeMap<>(
            (t1, t2) -> Integer.compare(t1.getLevel(), t2.getLevel())
        );
        sortedTiers.putAll(clients);

        // Find the lowest tier capable of satisfying the requirement
        for (Map.Entry<ProviderTier, List<LLMClient>> entry : sortedTiers.entrySet()) {
            if (entry.getKey().getLevel() >= requiredTier.getLevel() && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }

        // If no model exactly matches or exceeds requirement, just pick the highest tier we have
        ProviderTier highest = sortedTiers.lastKey();
        if (!sortedTiers.get(highest).isEmpty()) {
            return sortedTiers.get(highest).get(0);
        }

        throw new LLMException("Could not find an available LLMClient capable of handling the request.");
    }

    private ProviderTier determineRequiredTier(LLMRequest request) {
        // 1. Explicit Hint takes precedence
        if (request.getComplexityHint() != null) {
            return mapHintToTier(request.getComplexityHint());
        }

        // 2. Heuristic Inference
        // Check temp
        if (request.getTemperature() != null && request.getTemperature() >= DEFAULT_HIGH_TEMP) {
            return ProviderTier.REASONING; // High hallucination risk, use premium model
        }

        // Check context length
        int totalChars = 0;
        for (Message msg : request.getMessages()) {
            if (msg.getContent() != null) {
                totalChars += msg.getContent().length();
            }
        }
        
        if (totalChars > DEFAULT_LONG_CONTEXT_CHARS) {
            return ProviderTier.REASONING; // Cheap models degrade on massive contexts
        }

        // Check for reasoning keywords in system prompt
        for (Message msg : request.getMessages()) {
            if (Message.Role.SYSTEM == msg.getRole() && msg.getContent() != null) {
                String sysPrompt = msg.getContent().toLowerCase();
                if (sysPrompt.contains("think step") || 
                    sysPrompt.contains("expert mathematician") ||
                    sysPrompt.contains("logical reasoning")) {
                    return ProviderTier.REASONING;
                }
            }
        }

        // Default to cheap
        return ProviderTier.FAST_CHEAP;
    }

    private ProviderTier mapHintToTier(ComplexityHint hint) {
        switch (hint) {
            case HIGH:
                return ProviderTier.REASONING;
            case MEDIUM:
                return ProviderTier.BALANCED;
            case LOW:
            default:
                return ProviderTier.FAST_CHEAP;
        }
    }
}
