package io.github.llm4j.loom.execution;

import java.util.Locale;

/**
 * Names of built-in decision models other than Jev: Perplexity's Decisions API, served by {@code
 * pplx-decider-latest}, {@code pplx-decider-flash-latest} and the versioned {@code pplx-decider-…}
 * names, with or without a {@code perplexity/} prefix. They answer typed questions (an {@code
 * intentclassifier}'s), do not chat, and take the Perplexity key.
 */
public final class DecisionModels {

    private DecisionModels() {}

    /** True for a {@code pplx-decider-…} model name. */
    public static boolean isPerplexityDecider(String model) {
        if (model == null) return false;
        String m = model.toLowerCase(Locale.ROOT);
        return m.startsWith("pplx-decider") || m.startsWith("perplexity/pplx-decider");
    }
}
