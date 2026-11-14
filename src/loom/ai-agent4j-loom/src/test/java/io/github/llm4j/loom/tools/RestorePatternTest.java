package io.github.llm4j.loom.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.execution.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 35, D9 and G60 (the 35c spike): restore is a workspace operation a script calls, and a
 * rewind with {@code side effects: repeat} runs the attempt again. Attempt one leaves a scratch
 * file and fails its check; the rewind's new attempt restores the snapshot (deleting the scratch
 * file) and its identical write lands again. With {@code keep}, the identical restore of attempt
 * two is taken for the one attempt one already did and is not run, so the scratch file survives.
 */
class RestorePatternTest {

    @TempDir Path dir;

    static String script(String effects) {
        return """
                tool Data { use: workspace  root: "work"  allow: "**"  mode: readwrite  overwrite: true  store: "snaps" }

                workflow Main() {
                    use Data(action = "snapshot", name = "clean")
                    checkpoint Clean starting with attempt = "first"
                    use Data(action = "restore", name = "clean")
                    use Data(action = "write", path = "report.md", content = "final")
                    use Data(action = "write", path = "scratch-{attempt}.md", content = "tried")
                    rewind to Clean when (attempt == "first") at most 1 time carrying attempt = "second" side effects: EFFECTS
                }
                """
                .replace("EFFECTS", effects);
    }

    void run(String effects) throws Exception {
        Files.createDirectories(dir.resolve("work"));
        Files.writeString(dir.resolve("work/notes.md"), "kept\n");
        Path file = Files.writeString(dir.resolve("main.loom"), script(effects));
        var parsed = new LoomLoader().load(file.toString());
        HarnessExecutor e =
                new HarnessExecutor(
                        parsed,
                        new ToolRegistry(),
                        m -> {
                            throw new IllegalStateException();
                        });
        e.setBaseDir(dir);
        e.setEnvLookup(k -> null);
        e.setHumanInterface(m -> "keep");
        List<ScriptValidator.Problem> errors =
                new ScriptValidator()
                        .validate(parsed, e.validationContext()).stream()
                                .filter(p -> p.severity() == ScriptValidator.Severity.ERROR)
                                .toList();
        assertThat(errors).as("weave check").isEmpty();
        e.initialize();
        e.executeWorkflow("Main", Map.of());
    }

    @Test
    void aRewindWithRepeatRestoresTheWorkspaceAndRunsTheAttemptAgain() throws Exception {
        run("repeat");
        assertThat(dir.resolve("work/scratch-first.md")).doesNotExist();
        assertThat(dir.resolve("work/scratch-second.md")).hasContent("tried");
        assertThat(dir.resolve("work/report.md")).hasContent("final");
        assertThat(dir.resolve("work/notes.md")).hasContent("kept\n");
    }

    @Test
    void withKeepTheSecondRestoreIsTakenForTheFirstAndNothingIsUndone() throws Exception {
        run("keep");
        assertThat(dir.resolve("work/scratch-second.md")).as("attempt two ran").exists();
        assertThat(dir.resolve("work/scratch-first.md")).as("left by attempt one").exists();
    }
}
