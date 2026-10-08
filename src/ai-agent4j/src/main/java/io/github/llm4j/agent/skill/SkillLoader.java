package io.github.llm4j.agent.skill;

import java.io.IOException;

/** Strategy interface for loading {@link AgentSkill} instances from a given source string. */
public interface SkillLoader {

    /**
     * Loads a skill from the given source string (e.g. a filename or classpath path).
     *
     * @param source the source identifier
     * @return the loaded {@link AgentSkill}
     * @throws IOException if the skill cannot be loaded
     */
    AgentSkill load(String source) throws IOException;
}
