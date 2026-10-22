package io.github.llm4j.grounding;

/**
 * The settings of a {@link Grounding} that a provider may or may not be able to honour. A provider
 * lists the ones it can in {@code LLMProvider.groundingOptions()}; the client refuses (or, with
 * {@link Grounding#lenient()}, drops) the rest.
 */
public enum GroundingOption {
    /** {@link Grounding#maxSearches()}. */
    MAX_SEARCHES("maxSearches"),
    /** {@link Grounding#allowDomains()}. */
    ALLOW_DOMAINS("allowDomains"),
    /** {@link Grounding#blockDomains()}. */
    BLOCK_DOMAINS("blockDomains"),
    /** {@link Grounding#recency()}. */
    RECENCY("recency"),
    /** {@link Grounding#country()}. */
    COUNTRY("country");

    private final String label;

    GroundingOption(String label) {
        this.label = label;
    }

    /**
     * The name of the setting as the builder spells it ({@code maxSearches}), for messages and
     * metadata.
     */
    public String label() {
        return label;
    }
}
