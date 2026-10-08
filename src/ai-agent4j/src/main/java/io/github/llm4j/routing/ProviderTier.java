package io.github.llm4j.routing;

/**
 * Cost and capability tiers for LLM Providers.
 * Used by routing strategies to decide which underlying model to call for a specific task.
 */
public enum ProviderTier {
    /** The cheapest, fastest tier. Best for simple extraction, summarization, or basic tasks. */
    FAST_CHEAP(1),
    
    /** A middle-ground tier offering good reasoning at a moderate cost. */
    BALANCED(2),
    
    /** Premium tier models designed for complex multi-step reasoning, coding, and difficult problem solving. */
    REASONING(3);
    
    private final int level;
    
    ProviderTier(int level) {
        this.level = level;
    }
    
    public int getLevel() {
        return level;
    }
}
