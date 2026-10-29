package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F19: keys were typed into the generated {@code .env.example}. Every starter writes it with names
 * and empty values, says plainly not to put a key in it, and shows the secret store as the encrypted
 * alternative; and what it generates passes its own check.
 */
class GeneratedEnvExampleIsEmptyTest {
    @TempDir Path dir;

    @Test
    void everyStarterWritesAnEmptyExampleThatWarnsAndPointsAtTheStore() throws Exception {
        for (String template : new String[] {"pipeline", "approval", "classifier", "empty"}) {
            Path project = dir.resolve(template);
            var result = FieldCli.weave("init", template, project.toString(), "--flat");
            assertThat(result.exit()).as(template).isZero();

            String example = Files.readString(project.resolve(".env.example"));
            assertThat(example)
                    .as(template)
                    .contains("Never put a key in THIS file")
                    .contains("weave secrets import-env --from");
            assertThat(EnvExampleCheck.namesWithValuesIn(project.resolve(".env.example")))
                    .as(template + " has no value in its own example")
                    .isEmpty();
            assertThat(Files.readAllLines(project.resolve(".gitignore")))
                    .as(template)
                    .contains(".env", "*.store");
        }
    }
}
