package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.demo.Scenarios;
import io.github.llm4j.loom.wtest.TestRunner;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * R1 to R8 of spec 16: a narrated run of a real workflow on demo models, with nothing left behind.
 */
class TryCommandTest {

    @TempDir Path dir;

    record Out(int code, String out, String err) {}

    private Out run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        WeaveEnv env =
                new WeaveEnv(
                        m -> {
                            throw new IllegalStateException("try builds no model client");
                        },
                        q -> {
                            throw new IllegalStateException("nobody to ask");
                        },
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> null);
        TryCommand c = new TryCommand();
        new CommandLine(c).parseArgs(args);
        int code = TryCommand.run(c, env, new ByteArrayInputStream("\n\n\n\n\n\n\n\n".getBytes()));
        return new Out(
                code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theDefaultScenarioIsNarratedFromTheRealInterpreter() {
        Out r = run("--fast");
        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out())
                .contains("Demo models: every reply is pre-written")
                .contains("input  message = \"Where is my parcel?")
                .contains("1. classify: the classifier Intent answered kind = order_status")
                .contains("alt: Loom itself checked `intent.kind == \"order_status\"`")
                .contains("No model decides this.")
                .contains(
                        "delegate: the agent Replier was asked and answered: \"Your parcel left our warehouse")
                .contains("ask a person: the run stopped for a human. The answer, \"yes\"")
                .contains("return: the workflow ends here")
                .contains("path   classify Intent")
                .contains("this demo cost $0")
                .contains("weave try triage --keep ./demo")
                .contains("weave generate");
        assertThat(r.out().trim()).endsWith("Real models vary.");
    }

    @Test
    void theFlakyScenarioShowsTheFailuresRetryAbsorbs() {
        Out r = run("flaky", "--fast");
        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out()).contains("delegate: the agent Fetcher").contains("retry 3");
        assertThat(r.out()).contains("Mount Kenya is 5,199 metres high.");
    }

    @Test
    void listNamesEveryScenarioAndWhatItShows() {
        Out r = run("--list");
        assertThat(r.out())
                .contains("triage")
                .contains("flaky")
                .contains("shows: intentclassify, alt, delegate, human_prompt, return")
                .contains("retry, backoff, timeout, on_failure");
    }

    @Test
    void anUnknownScenarioNamesTheKnownOnes() {
        Out r = run("nope");
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err())
                .contains("no scenario \"nope\"")
                .contains("triage")
                .contains("weave try --list");
    }

    @Test
    void nothingIsWrittenUnlessKeepIsGivenAndTheTempFolderIsGone() throws Exception {
        Path before = Path.of(System.getProperty("java.io.tmpdir"));
        long tours = countTours(before);
        Path cwd = Path.of("").toAbsolutePath();
        long files = Files.list(cwd).count();
        run("--fast");
        assertThat(countTours(before)).isEqualTo(tours);
        assertThat(Files.list(cwd).count()).isEqualTo(files);
    }

    private static long countTours(Path tmp) throws Exception {
        try (Stream<Path> s = Files.list(tmp)) {
            return s.filter(p -> p.getFileName().toString().startsWith("weave-try-")).count();
        }
    }

    @Test
    void keepLeavesAProjectWhoseTestsPassAndRefusesToOverwrite() throws Exception {
        for (Scenarios.Scenario s : Scenarios.list()) {
            Path out = dir.resolve(s.name());
            Out r = run(s.name(), "--keep", out.toString());
            assertThat(r.code()).as(r.err()).isZero();
            var suite =
                    TestRunner.load(
                            out.resolve("main.loom"), List.of(out.resolve("tests/main.test.yaml")));
            assertThat(suite.problems()).isEmpty();
            var results = TestRunner.run(suite, null, false, false);
            assertThat(results)
                    .isNotEmpty()
                    .allMatch(x -> x.passed(), "every case of " + s.name() + " passes");
            assertThat(run(s.name(), "--keep", out.toString()).err())
                    .contains("already has a main.loom");
        }
    }

    @Test
    void stepWaitsForEnterAndStillFinishes() {
        Out r = run("--step");
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("(Enter for the next step)").contains("return:");
    }
}
