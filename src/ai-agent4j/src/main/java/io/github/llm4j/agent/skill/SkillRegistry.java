package io.github.llm4j.agent.skill;

import java.io.IOException;
import java.util.List;

/**
 * Interface for discovering and fetching skills from an external registry (e.g. SkillsMP).
 */
public interface SkillRegistry {

    /**
     * Searches for skills matching the given query.
     *
     * @param query the search query
     * @return a list of matching skill metadata
     * @throws IOException if there is an error communicating with the registry
     */
    List<SkillMetadata> searchSkills(String query) throws IOException;

    /**
     * Fetches a specific skill by its ID.
     *
     * @param skillId the unique identifier of the skill
     * @return the loaded agent skill
     * @throws IOException if the skill cannot be fetched or parsed
     */
    AgentSkill getSkill(String skillId) throws IOException;
}
