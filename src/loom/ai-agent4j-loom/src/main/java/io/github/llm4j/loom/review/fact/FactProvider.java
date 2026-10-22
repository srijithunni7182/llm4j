package io.github.llm4j.loom.review.fact;

/**
 * Computes facts. A provider is pure Java: it reads the review input and fills entities; it runs no
 * model, tool or network call, and it is the only thing that can set a fact. Rules, which are data,
 * only read what providers computed.
 */
public interface FactProvider {

    /** A short name for messages. */
    String name();

    /**
     * Adds entities and sets their facts. Providers run in a fixed order, each seeing what earlier
     * ones added.
     */
    void provide(FactInput input, FactBase.Builder out);
}
