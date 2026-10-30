package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.dataset.EvalScenarios;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 19 R16 at the command: derived cases are written beside the originals, never over them. */
class MetamorphicCommandTest {
    @TempDir Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private int run(String... args) {
        WeaveEnv env =
                new WeaveEnv(
                        m -> null,
                        q -> "yes",
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                        List.of("weave"),
                        k -> null);
        MetamorphicCommand c = new MetamorphicCommand();
        new picocli.CommandLine(c).parseArgs(args);
        return MetamorphicCommand.run(c, env);
    }

    private Path dataset() throws Exception {
        Path golden = Files.createDirectories(dir.resolve("golden"));
        Files.writeString(
                golden.resolve("helper.yaml"),
                "- {id: a, input: \"First fact. Second fact.\", expected_output_contains: x}\n- {id: b, input: \"One only.\", expected_output_contains: y}\n");
        return golden;
    }

    @Test
    void derivedCasesAreWrittenToANewFolderWithTheOriginalsKept() throws Exception {
        Path golden = dataset();
        Path target = dir.resolve("meta");

        assertThat(run("--from", golden.toString(), "--out", target.toString())).isZero();

        var cases = EvalScenarios.fromYaml(target.resolve("helper.yaml"));
        assertThat(cases).extracting("id").contains("a", "b", "a~irrelevant", "a~order", "b~irrelevant");
        assertThat(cases).extracting("id").doesNotContain("b~order");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("skipped: b: order").contains("weave eval <script> --dataset");
        assertThat(Files.readString(golden.resolve("helper.yaml"))).doesNotContain("~");
    }

    @Test
    void aFolderThatIsNotEmptyIsNeverWrittenOver() throws Exception {
        Path golden = dataset();
        Path target = Files.createDirectories(dir.resolve("meta"));
        Files.writeString(target.resolve("keep.txt"), "x");

        assertThat(run("--from", golden.toString(), "--out", target.toString())).isEqualTo(2);

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("is not empty");
    }

    @Test
    void aRelationThatNeedsAModelIsRefusedWithWhy() throws Exception {
        assertThat(run("--from", dataset().toString(), "--out", dir.resolve("m").toString(), "--relations", "paraphrase")).isEqualTo(2);

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("paraphrase needs a model");
    }
}
