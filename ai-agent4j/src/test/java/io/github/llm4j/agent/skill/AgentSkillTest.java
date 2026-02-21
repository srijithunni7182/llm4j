package io.github.llm4j.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSkillTest {

    @TempDir Path tempDir;

    @Test
    void testSkillCreationWithOf() {
        AgentSkill skill = AgentSkill.of("My Skill", "Some content");
        assertThat(skill.getName()).isEqualTo("My Skill");
        assertThat(skill.getContent()).isEqualTo("Some content");
    }

    @Test
    void testSkillFromFile() throws IOException {
        Path file = tempDir.resolve("my-skill.md");
        Files.writeString(file, "Hello from file", StandardCharsets.UTF_8);

        AgentSkill skill = AgentSkill.fromFile(file);
        assertThat(skill.getContent()).isEqualTo("Hello from file");
    }

    @Test
    void testSkillFromClasspath() throws IOException {
        AgentSkill skill = AgentSkill.fromClasspath("skills/test-skill.md");
        assertThat(skill.getContent()).contains("This is a test skill");
        assertThat(skill.getName()).isEqualTo("Test Skill");
    }

    @Test
    void testSkillNameInferredFromFilename() throws IOException {
        Path file = tempDir.resolve("coding-tips.md");
        Files.writeString(file, "some tips", StandardCharsets.UTF_8);

        AgentSkill skill = AgentSkill.fromFile(file);
        assertThat(skill.getName()).isEqualTo("Coding Tips");
    }

    @Test
    void testSkillNameUnderscoreToDash() throws IOException {
        Path file = tempDir.resolve("security_guidelines.md");
        Files.writeString(file, "security content", StandardCharsets.UTF_8);

        AgentSkill skill = AgentSkill.fromFile(file);
        assertThat(skill.getName()).isEqualTo("Security Guidelines");
    }

    @Test
    void testSkillToSystemPromptSection() {
        AgentSkill skill = AgentSkill.of("Test Skill", "skill content here");
        String section = skill.toSystemPromptSection();
        assertThat(section).isEqualTo("### Test Skill\nskill content here");
    }

    @Test
    void testNullNameThrows() {
        assertThatThrownBy(() -> AgentSkill.of(null, "content"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testNullContentThrows() {
        assertThatThrownBy(() -> AgentSkill.of("Name", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testBlankNameThrows() {
        assertThatThrownBy(() -> AgentSkill.of("  ", "content"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testFromNonExistentFileThrows() {
        assertThrows(IOException.class, () -> AgentSkill.fromFile(Path.of("/nonexistent/file.md")));
    }

    @Test
    void testFromMissingClasspathResourceThrows() {
        assertThrows(IOException.class, () -> AgentSkill.fromClasspath("skills/missing.md"));
    }

    @Test
    void testGetters() {
        AgentSkill skill = AgentSkill.of("My Name", "My Content");
        assertThat(skill.getName()).isEqualTo("My Name");
        assertThat(skill.getContent()).isEqualTo("My Content");
    }
}
