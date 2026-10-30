package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 22 R9 through the command: a key that is not set does not fail {@code --fail-on}. The model
 * is one that needs GEMINI_API_KEY, which these tests report as not set whatever the machine has.
 */
class ReviewSetupCommandTest {

    @TempDir Path dir;

    /** The key has to be missing for the test to mean anything; a machine that has it set skips it. */
    private static void needsTheKeyToBeMissing() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("GEMINI_API_KEY") == null,
                "GEMINI_API_KEY is set on this machine, so a missing key cannot be shown");
    }

    private record Out(int code, String text) {}

    private Out review(String... extra) throws Exception {
        Path script =
                Files.writeString(
                        dir.resolve("main.loom"),
                        "agent A { model: \"gemini-2.5-flash\" system: \"You help.\" }\n"
                                + "workflow W(t) {\n delegate \"x {t}\" to A -> r\n return r\n}\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WeaveEnv env =
                new WeaveEnv(
                        new io.github.llm4j.loom.execution.DefaultLLMClientFactory(),
                        q -> "yes",
                        new PrintStream(out, true),
                        new PrintStream(new ByteArrayOutputStream(), true),
                        java.time.Clock.systemUTC(),
                        d -> {},
                        c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                        java.util.List.of("weave"),
                        System::getenv);
        ReviewCommand c = new ReviewCommand();
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(script.toString(), "--no-env-file"));
        args.addAll(java.util.List.of(extra));
        new picocli.CommandLine(c).parseArgs(args.toArray(String[]::new));
        return new Out(ReviewCommand.review(c, env), out.toString());
    }

    @Test
    void aMissingKeyIsListedAsSetupAndDoesNotFailFailOn() throws Exception {
        needsTheKeyToBeMissing();
        Out r = review("--fail-on", "high");

        assertThat(r.code()).isZero();
        assertThat(r.text()).contains("Setup").contains("GEMINI_API_KEY");
        assertThat(r.text()).doesNotContain("HIGH    LV02");
    }

    @Test
    void includeSetupCountsItAgain() throws Exception {
        needsTheKeyToBeMissing();
        Out r = review("--fail-on", "high", "--include-setup");

        assertThat(r.code()).isEqualTo(1);
        assertThat(r.text()).contains("HIGH    LV02");
    }
}
