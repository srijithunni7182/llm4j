package io.github.llm4j.agent.prompt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class FileSystemPromptRegistryTest {

    private Path tempDir;
    private Path promptsFile;
    private FileSystemPromptRegistry registry;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("prompts-test");
        promptsFile = tempDir.resolve("prompts.yaml");

        // Create initial content
        String content = "prompts:\n" +
                "  test-prompt:\n" +
                "    v1: \"Version 1\"\n" +
                "    v2: \"Version 2\"\n" +
                "    latest: \"v2\"";
        Files.writeString(promptsFile, content);

        registry = new FileSystemPromptRegistry(promptsFile);
    }

    @AfterEach
    void tearDown() throws Exception {
        registry.close();
        Files.deleteIfExists(promptsFile);
        Files.deleteIfExists(tempDir);
    }

    @Test
    void testLoadInitialPrompts() {
        Optional<PromptTemplate> latest = registry.get("test-prompt");
        assertThat(latest).isPresent();
        assertThat(latest.get().getVersion()).isEqualTo("v2");
        assertThat(latest.get().getTemplate()).isEqualTo("Version 2");

        Optional<PromptTemplate> v1 = registry.get("test-prompt", "v1");
        assertThat(v1).isPresent();
        assertThat(v1.get().getTemplate()).isEqualTo("Version 1");
    }

    @Test
    void testHotReload() throws IOException {
        // Update file
        String newContent = "prompts:\n" +
                "  test-prompt:\n" +
                "    v1: \"Version 1\"\n" +
                "    v2: \"Version 2\"\n" +
                "    v3: \"Version 3\"\n" +
                "    latest: \"v3\"";

        Files.writeString(promptsFile, newContent);

        // Wait for reload manually to avoid extra dependency
        for (int i = 0; i < 20; i++) {
            Optional<PromptTemplate> latest = registry.get("test-prompt");
            if (latest.isPresent() && "v3".equals(latest.get().getVersion())) {
                assertThat(latest.get().getTemplate()).isEqualTo("Version 3");
                return;
            }
            Thread.sleep(100);
        }
        fail("Hot reload prompt update not detected");
    }
}
