package io.github.llm4j.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ClasspathSkillLoaderTest {

    @Test
    void testLoadFromClasspathResource() throws IOException {
        ClasspathSkillLoader loader = new ClasspathSkillLoader();
        AgentSkill skill = loader.load("skills/test-skill.md");

        assertThat(skill).isNotNull();
        assertThat(skill.getName()).isEqualTo("Test Skill");
    }

    @Test
    void testLoadFromMissingResourceThrows() {
        ClasspathSkillLoader loader = new ClasspathSkillLoader();
        assertThrows(IOException.class, () -> loader.load("skills/missing-resource.md"));
    }

    @Test
    void testLoadedSkillHasCorrectContent() throws IOException {
        ClasspathSkillLoader loader = new ClasspathSkillLoader();
        AgentSkill skill = loader.load("skills/test-skill.md");

        assertThat(skill.getContent()).contains("This is a test skill for unit testing");
        assertThat(skill.getContent()).contains("Point one");
        assertThat(skill.getContent()).contains("Point two");
    }
}
