package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.init.Templates;
import io.github.llm4j.loom.wtest.TestRunner;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Every starter project comes with tests that pass and cover its branches, in both layouts. */
class InitTemplateTestsPassTest {

    @TempDir Path dir;

    private void init(String template, Path target, boolean flat) {
        InitCommand c = new InitCommand();
        new CommandLine(c)
                .parseArgs(
                        flat
                                ? new String[] {template, target.toString(), "--flat"}
                                : new String[] {template, target.toString()});
        WeaveEnv env =
                new WeaveEnv(
                        m -> {
                            throw new IllegalStateException("no models");
                        },
                        q -> "yes",
                        new PrintStream(new ByteArrayOutputStream(), true),
                        new PrintStream(new ByteArrayOutputStream(), true),
                        Clock.systemUTC(),
                        d -> {},
                        r ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> null);
        assertThat(InitCommand.init(c, env)).isZero();
    }

    private void passes(Path testsDir, Path script) throws Exception {
        TestRunner.Suite suite = TestRunner.load(script, List.of(testsDir));
        assertThat(suite.problems()).isEmpty();
        List<TestRunner.CaseResult> results = TestRunner.run(suite, null, false, false);
        assertThat(results).isNotEmpty();
        for (TestRunner.CaseResult r : results) assertThat(r.failures()).as(r.name()).isEmpty();
        assertThat(TestRunner.coverage(suite, results).missed())
                .as("every statement of the starter workflow is run by its tests")
                .isEmpty();
    }

    @Test
    void mavenLayoutHasItsTestsAmongTheTestResources() throws Exception {
        for (Templates.Template t : Templates.all()) {
            Path p = dir.resolve("m-" + t.name());
            init(t.name(), p, false);
            Path tests = p.resolve("src/test/resources/loom-tests");
            assertThat(Files.isRegularFile(tests.resolve("main.test.yaml"))).as(t.name()).isTrue();
            passes(tests, p.resolve("src/main/resources/main.loom"));
        }
    }

    @Test
    void flatLayoutHasATestsFolder() throws Exception {
        for (Templates.Template t : Templates.all()) {
            Path p = dir.resolve("f-" + t.name());
            init(t.name(), p, true);
            passes(p.resolve("tests"), p.resolve("main.loom"));
        }
    }
}
