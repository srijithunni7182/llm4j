package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.bind.BindgenTest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** R9, R10 of spec 15: no output from a script that does not check, and drift is caught. */
class BindgenCommandTest {

    @TempDir Path dir;

    record Out(int code, String out, String err) {}

    private Out bindgen(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        WeaveEnv env =
                new WeaveEnv(
                        m -> {
                            throw new IllegalStateException("bindgen calls no model");
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
        BindgenCommand c = new BindgenCommand();
        new CommandLine(c).parseArgs(args);
        int code = BindgenCommand.run(c, env);
        return new Out(
                code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path script(String text) throws Exception {
        Path p = dir.resolve("triage.loom");
        Files.writeString(p, text);
        return p;
    }

    @Test
    void writesCheckPassesAndATouchedScriptFailsTheCheck() throws Exception {
        Path s = script(BindgenTest.SCRIPT);
        Path out = dir.resolve("src");
        Out first = bindgen(s.toString(), "--package", "com.acme", "--out", out.toString());
        assertThat(first.code()).as(first.err()).isZero();
        assertThat(out.resolve("com/acme/TriageWorkflows.java")).exists();
        assertThat(out.resolve("com/acme/Outcome.java")).exists();
        assertThat(
                        bindgen(
                                        s.toString(),
                                        "--package",
                                        "com.acme",
                                        "--out",
                                        out.toString(),
                                        "--check")
                                .out())
                .contains("up to date");
        // a field renamed in the script, not regenerated
        Files.writeString(s, BindgenTest.SCRIPT.replace("team: \"refunds\"", "squad: \"refunds\""));
        Out drift =
                bindgen(s.toString(), "--package", "com.acme", "--out", out.toString(), "--check");
        assertThat(drift.code()).isEqualTo(1);
        assertThat(drift.err())
                .contains("out of date")
                .contains("TriageWorkflows.java")
                .contains("weave bindgen");
        // --check wrote nothing
        assertThat(Files.readString(out.resolve("com/acme/TriageWorkflows.java")))
                .contains("String team");
        // regenerating makes the old field name disappear: code that used it would not compile
        assertThat(bindgen(s.toString(), "--package", "com.acme", "--out", out.toString()).code())
                .isZero();
        assertThat(Files.readString(out.resolve("com/acme/TriageWorkflows.java")))
                .contains("String squad");
    }

    @Test
    void aScriptThatDoesNotCheckWritesNothing() throws Exception {
        Path s = script("workflow Main(x) {\n    delegate \"hi\" to Nobody -> r\n}\n");
        Path out = dir.resolve("src");
        Out r = bindgen(s.toString(), "--package", "com.acme", "--out", out.toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("does not check");
        assertThat(Files.exists(out)).isFalse();
    }

    @Test
    void badArgumentsAreSaid() throws Exception {
        Path s = script(BindgenTest.SCRIPT);
        assertThat(bindgen(s.toString(), "--package", "com.acme", "--nullability", "maybe").err())
                .contains("none, jspecify or optional");
        assertThat(bindgen(dir.resolve("none.loom").toString(), "--package", "com.acme").err())
                .contains("is not there");
        assertThat(
                        bindgen(
                                        s.toString(),
                                        "--package",
                                        "Bad.Pkg",
                                        "--out",
                                        dir.resolve("o").toString())
                                .err())
                .contains("--package");
    }
}
