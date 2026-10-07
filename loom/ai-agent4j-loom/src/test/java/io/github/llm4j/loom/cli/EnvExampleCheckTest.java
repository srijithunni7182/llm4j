package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A key written into the committed .env.example is flagged by name; empty values and placeholders are not. */
class EnvExampleCheckTest {

    @TempDir Path dir;

    private Path project(String envExample) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Path script = dir.resolve("src/main/resources/main.loom");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "workflow Main(text) {\n    note \"{text}\"\n}\n");
        Files.writeString(dir.resolve(".env.example"), envExample);
        return script;
    }

    @Test
    void realLookingValueIsFlaggedByNameOnly() throws Exception {
        Path script = project("# keys\nOPENAI_API_KEY=sk-live-abcdef123456\nEMPTY=\nOTHER=your-key-here\n");
        assertThat(EnvExampleCheck.namesWithValues(script)).containsExactly("OPENAI_API_KEY");
        assertThat(EnvExampleCheck.warning(EnvExampleCheck.namesWithValues(script))).contains("OPENAI_API_KEY").doesNotContain("sk-live");
    }

    @Test
    void templateWithEmptyValuesIsQuiet() throws Exception {
        assertThat(EnvExampleCheck.namesWithValues(project("OPENAI_API_KEY=\nANTHROPIC_API_KEY=\n"))).isEmpty();
    }
}
