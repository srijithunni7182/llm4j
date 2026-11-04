package io.github.llm4j.model;

/**
 * Whether a client can make a model answer with JSON that follows a schema, as part of the request.
 * Support is a fact about a provider and a model, recorded from the vendor's documentation, so a
 * client answers per model name and an unknown model answers {@link #NONE}.
 */
public enum StructuredOutput {
    /** The reply is not constrained; ask for JSON in the prompt and check what comes back. */
    NONE,
    /** The provider constrains the reply to the JSON Schema of {@link ResponseFormat}. */
    JSON_SCHEMA
}
