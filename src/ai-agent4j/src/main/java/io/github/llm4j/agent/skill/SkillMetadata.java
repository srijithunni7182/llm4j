package io.github.llm4j.agent.skill;

import java.util.List;

/**
 * Metadata for an agent skill discovered from a registry.
 */
public record SkillMetadata(
        String id,
        String name,
        String description,
        String author,
        List<String> tags
) {}
