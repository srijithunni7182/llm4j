package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R49, R53 of spec 09 (program promise P10): review findings never change what {@code weave run}
 * does. With the notice on or off, standard output, the exit code and every file the run leaves
 * behind are identical; the only difference is the one line on standard error.
 */
class RunUnchangedWithNoticeTest {

    @TempDir Path dir;
    PrintStream realOut;
    PrintStream realErr;

    @BeforeEach
    void keep() {
        realOut = System.out;
        realErr = System.err;
        ReviewNotice.reset();
        ReviewNotice.defaultCapMillis = 60_000;
    }

    @AfterEach
    void restore() {
        System.setOut(realOut);
        System.setErr(realErr);
        ReviewNotice.defaultCapMillis = ReviewNotice.DEFAULT_CAP_MILLIS;
        ReviewNotice.reset();
    }

    record Outcome(int exit, String stdout, String stderr, List<String> files) {}

    // a script with an open review finding (no run budget) that runs without any model
    static final String SCRIPT =
            "agent Spare { model: \"ollama/llama3\"  system: \"s\" }\nworkflow Main() {\n  note \"hello from the run\"\n}\n";

    Outcome run(String name, String... extra) throws Exception {
        Path work = Files.createDirectories(dir.resolve(name));
        Path script = work.resolve("w.loom");
        Files.writeString(script, SCRIPT);
        Files.writeString(work.resolve("review.properties"), "lenses = +maintainability\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        ReviewNotice.reset();
        List<String> args =
                new ArrayList<>(
                        List.of(
                                "run",
                                script.toString(),
                                "--journal",
                                work.resolve("run").toString()));
        args.addAll(List.of(extra));
        int exit = WeaveCLI.commandLine().execute(args.toArray(new String[0]));
        // give the notice thread the time it needs; a real command does not wait for it
        long until = System.currentTimeMillis() + (name.equals("off") ? 300 : 10_000);
        while (System.currentTimeMillis() < until
                && !err.toString(StandardCharsets.UTF_8).contains("weave review")
                && !err.toString(StandardCharsets.UTF_8).contains("review unavailable"))
            Thread.sleep(50);
        System.setOut(realOut);
        System.setErr(realErr);
        List<String> files;
        try (Stream<Path> s = Files.walk(work.resolve("run"))) {
            files =
                    s.filter(Files::isRegularFile)
                            .map(p -> work.relativize(p) + ":" + read(p))
                            .sorted()
                            .toList();
        }
        return new Outcome(
                exit,
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8),
                files);
    }

    private String read(Path p) {
        try {
            String text = Files.readString(p);
            // the journal records absolute paths and times; compare the shape, not the machine
            return text.replace(dir.toString(), "D")
                    .replaceAll("/(on|off)/", "/X/")
                    .replaceAll("\\d{10,}", "N");
        } catch (Exception e) {
            return "?";
        }
    }

    @Test
    void theNoticeAddsOneLineOnStandardErrorAndNothingElseChanges() throws Exception {
        Outcome on = run("on");
        Outcome off = run("off", "--review-notice", "off");
        assertThat(on.stderr()).contains("⚠ weave review:").contains("not blocking");
        assertThat(off.stderr()).doesNotContain("weave review");
        assertThat(on.exit()).isEqualTo(off.exit());
        assertThat(on.stdout()).isEqualTo(off.stdout());
        assertThat(on.files().stream().map(f -> f.replaceFirst("^(on|off)/", "")))
                .containsExactlyElementsOf(
                        off.files().stream().map(f -> f.replaceFirst("^(on|off)/", "")).toList());
        assertThat(on.stdout()).contains("Workflow completed successfully");
    }

    @Test
    void findingsNeverMakeARunFailEvenWithAFailOnSettingInTheProject() throws Exception {
        Path work = Files.createDirectories(dir.resolve("strict"));
        Files.writeString(
                work.resolve("review.properties"), "fail-on = info\nlenses = +maintainability\n");
        Path script = work.resolve("w.loom");
        Files.writeString(script, SCRIPT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true));
        int exit = WeaveCLI.commandLine().execute("run", script.toString());
        System.setOut(realOut);
        assertThat(exit).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("Workflow completed successfully");
    }
}
