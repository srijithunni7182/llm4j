package io.github.llm4j.agent.skill;

import java.io.IOException;
import java.util.Objects;

/**
 * A {@link SkillLoader} that loads skills from classpath resources.
 *
 * <p>The source string is interpreted as a classpath resource path (e.g. {@code
 * "skills/my-skill.md"}).
 */
public class ClasspathSkillLoader implements SkillLoader {

    /**
     * Loads a skill from the given classpath resource path.
     *
     * @param resourcePath the classpath resource path
     * @return the loaded {@link AgentSkill}
     * @throws IOException if the resource cannot be found or read
     */
    @Override
    public AgentSkill load(String resourcePath) throws IOException {
        Objects.requireNonNull(resourcePath, "resourcePath cannot be null");
        return AgentSkill.fromClasspath(resourcePath);
    }
}
