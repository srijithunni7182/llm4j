package io.github.llm4j.agent.prompt;

import java.util.Optional;

/**
 * Registry for managing and retrieving prompt templates.
 */
public interface PromptRegistry {

    /**
     * Get the latest version of a prompt template.
     * 
     * @param id The prompt identifier
     * @return The template if found
     */
    Optional<PromptTemplate> get(String id);

    /**
     * Get a specific version of a prompt template.
     * 
     * @param id      The prompt identifier
     * @param version The specific version
     * @return The template if found
     */
    Optional<PromptTemplate> get(String id, String version);

    /**
     * Reload the registry from source.
     */
    void reload();
}
