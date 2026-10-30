package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loading a script must not lose anything it declares. {@code LoomScript.merge} once dropped the
 * shared prompt, {@code supervise} and {@code intervention}, so a script run by any {@code weave}
 * command never applied them.
 */
class LoaderKeepsEveryDeclarationTest {
    @TempDir Path dir;

    private LoomScript load(String name, String text) throws IOException {
        Files.writeString(dir.resolve(name), text);
        return new LoomLoader().load(dir.resolve(name).toString());
    }

    private static final String ALL =
            """
            shared_prompt: "Rules for all."
            intervention { timeout: retry }
            budget { tokens: 1000 }
            agent A { model: "m" system: "You are A." }
            supervise A { when steps > 15 { cancel use partial } }
            workflow Main() { delegate "hi" to A -> out }
            """;

    @Test
    void aLoadedScriptKeepsTheSharedPromptTheSuperviseBlockAndTheInterventionDefaults()
            throws IOException {
        LoomScript script = load("main.loom", ALL);

        assertThat(script.getSharedPrompt()).isEqualTo("Rules for all.");
        assertThat(script.getSupervisions()).hasSize(1);
        assertThat(script.getIntervention()).isNotNull();
        assertThat(script.getBudget()).isNotNull();
    }

    @Test
    void aSharedPromptFileReferenceSurvivesLoadingWithItsLine() throws IOException {
        LoomScript script =
                load("ref.loom", "shared_prompt: prompt \"house-rules\"\nagent A { model: \"m\" }\n");

        assertThat(script.getSharedPromptRef()).isEqualTo("house-rules");
        assertThat(script.getSharedPromptLine()).isEqualTo(1);
    }

    @Test
    void supervisionInAnImportedFileStillWatchesItsAgents() throws IOException {
        Files.writeString(
                dir.resolve("lib.loom"),
                """
                agent Lib { model: "m" }
                supervise Lib { when steps > 3 { wrap_up } }
                """);
        LoomScript script =
                load(
                        "importer.loom",
                        "import \"lib.loom\"\nworkflow Main() { delegate \"x\" to Lib -> o }\n");

        assertThat(script.getSupervisions()).extracting(s -> s.getAgent()).containsExactly("Lib");
    }

    @Test
    void anImportedFilesSharedPromptIsNotImposedOnTheImporter() throws IOException {
        Files.writeString(
                dir.resolve("lib.loom"), "shared_prompt: \"From the library.\"\nagent Lib { model: \"m\" }\n");
        LoomScript script = load("importer.loom", "import \"lib.loom\"\nagent B { model: \"m\" }\n");

        assertThat(script.getSharedPrompt()).isNull();
    }
}
