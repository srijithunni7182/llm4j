package io.github.llm4j.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemSkillLoaderTest {

    @TempDir Path tempDir;

    @Test
    void testLoadFromAbsolutePath() throws IOException {
        Path file = tempDir.resolve("my-skill.md");
        Files.writeString(file, "absolute content", StandardCharsets.UTF_8);

        FileSystemSkillLoader loader = new FileSystemSkillLoader();
        AgentSkill skill = loader.load(file.toString());

        assertThat(skill.getContent()).isEqualTo("absolute content");
    }

    @Test
    void testLoadWithBaseDirectory() throws IOException {
        Path file = tempDir.resolve("relative-skill.md");
        Files.writeString(file, "relative content", StandardCharsets.UTF_8);

        FileSystemSkillLoader loader = new FileSystemSkillLoader(tempDir);
        AgentSkill skill = loader.load("relative-skill.md");

        assertThat(skill.getContent()).isEqualTo("relative content");
    }

    @Test
    void testLoadFromMissingFileThrows() {
        FileSystemSkillLoader loader = new FileSystemSkillLoader(tempDir);
        assertThrows(IOException.class, () -> loader.load("nonexistent.md"));
    }

    @Test
    void testLoadedSkillHasCorrectName() throws IOException {
        Path file = tempDir.resolve("coding-tips.md");
        Files.writeString(file, "some tips content", StandardCharsets.UTF_8);

        FileSystemSkillLoader loader = new FileSystemSkillLoader(tempDir);
        AgentSkill skill = loader.load("coding-tips.md");

        assertThat(skill.getName()).isEqualTo("Coding Tips");
    }
}
