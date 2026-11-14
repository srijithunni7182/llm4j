package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 35, D17 and G67: a scenario in steps runs them in order, each finding what the one before
 * left, with each step's checks named after it; and the cost per passing case has its interval.
 */
class EvalStepsTest {

    @TempDir Path dir;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();

    static final String SCRIPT =
            """
            tool Notes { use: workspace  root: "notes"  allow: "**"  mode: readwrite }
            workflow Main(request) {
                use Notes(action = "append", path = "log.md", content = request)
                use Notes(action = "read", path = "log.md") -> log
                return log.content
            }
            """;

    int eval(String dataset, String... more) throws Exception {
        Files.createDirectories(dir.resolve("notes"));
        Files.createDirectories(dir.resolve("eval/golden"));
        Files.writeString(dir.resolve("main.loom"), SCRIPT);
        Files.writeString(dir.resolve("eval/golden/workflow.yaml"), dataset);
        PrintStream p = new PrintStream(out, true, StandardCharsets.UTF_8);
        WeaveEnv env = new WeaveEnv(
                model -> {
                    throw new IllegalStateException("no model is asked");
                },
                m -> "yes", p, p, Clock.systemUTC(), d -> {},
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                List.of("weave"), k -> null);
        EvalCommand c = new EvalCommand();
        String[] args = new String[more.length + 2];
        args[0] = dir.resolve("main.loom").toString();
        args[1] = "-y";
        System.arraycopy(more, 0, args, 2, more.length);
        new picocli.CommandLine(c).parseArgs(args);
        return EvalCommand.eval(c, env);
    }

    String printed() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void stepsRunInOrderAndEachFindsWhatTheOneBeforeLeft() throws Exception {
        eval("""
                - name: two notes
                  expected_output_contains: "second"
                  steps:
                    - input: "first note"
                      expected_output_contains: "first note"
                    - input: "second note"
                      expected_output_contains: "first note"
                      expected_output_not_contains: "third"
                """);
        assertThat(Files.readString(dir.resolve("notes/log.md"))).contains("first note").contains("second note");
        assertThat(Files.readString(dir.resolve("notes/log.md")).indexOf("first")).isLessThan(Files.readString(dir.resolve("notes/log.md")).indexOf("second"));
        assertThat(printed()).contains("1 passed, 0 failed");
    }

    @Test
    void aStepThatFailsIsNamedAndTheScenarioFails() throws Exception {
        eval("""
                - name: wrong second
                  steps:
                    - input: "alpha"
                      expected_output_contains: "alpha"
                    - input: "beta"
                      expected_output_contains: "gamma"
                """);
        assertThat(printed()).contains("0 passed, 1 failed").contains("step 2 · answer contains");
    }

    @Test
    void aScenarioWithStepsTakesNoInputOfItsOwn() throws Exception {
        eval("""
                - name: both
                  input: "top"
                  steps:
                    - input: "a"
                """);
        assertThat(printed()).contains("move input: and inputs: into the steps");
    }

    @Test
    void costPerPassHasItsInterval() {
        assertThat(io.github.llm4j.loom.eval.CostPerPass.interval(new BigDecimal("1.00"), 5, 10))
                .isEqualTo(" (95% interval $0.1310 to $0.4227, from 5 of 10 judged)");
        assertThat(io.github.llm4j.loom.eval.CostPerPass.interval(new BigDecimal("1.00"), 0, 10)).isEmpty();
        assertThat(io.github.llm4j.loom.eval.CostPerPass.interval(BigDecimal.ZERO, 5, 10)).as("nothing spent: a mock").isEmpty();
        assertThat(io.github.llm4j.loom.eval.CostPerPass.interval(null, 5, 10)).isEmpty();
        var json = io.github.llm4j.loom.eval.CostPerPass.json(new BigDecimal("2"), 4, 4);
        assertThat(json).containsEntry("value", "0.5").containsEntry("judged", 4L);
        assertThat(json.get("high")).isNotNull();
        assertThat(io.github.llm4j.loom.eval.CostPerPass.json(new BigDecimal("2"), 0, 4)).containsEntry("value", null).containsEntry("high", null);
        assertThat(io.github.llm4j.loom.eval.CostPerPass.json(null, 1, 1)).isNull();
    }
}
