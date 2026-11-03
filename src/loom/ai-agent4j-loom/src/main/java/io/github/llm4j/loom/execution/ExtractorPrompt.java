package io.github.llm4j.loom.execution;

/**
 * The fixed words of an extractor's request that other parts of Loom recognise, so a mock model
 * (free runs, {@code weave eval --mock}) can tell an extraction from an agent's turn and answer with
 * a well-formed value of the schema.
 */
public final class ExtractorPrompt {

    /** The first words of every extractor's system prompt. */
    public static final String ROLE = "You are a field extractor.";

    /** Written just before the schema, which is rendered once. */
    public static final String SCHEMA_MARKER = "following this schema: ";

    private ExtractorPrompt() {}
}
