package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R30, R31 of spec 16: the commands on the Getting started page are the ones a newcomer types, and
 * they work with no key and no network, in at most six commands.
 */
class NewcomerJourneyTest {

    @TempDir Path dir;

    private static List<String> documented() throws Exception {
        String text = Files.readString(Path.of("../../../docs/GETTING_STARTED.md"));
        List<String> out = new ArrayList<>();
        boolean in = false;
        for (String l : text.split("\n")) {
            if (l.startsWith("```")) {
                in = !in;
                continue;
            }
            if (in && l.startsWith("$ weave ")) out.add(l.substring(2));
        }
        return out;
    }

    private record Ran(int code, String out, String err) {}

    private static Ran weave(String line, Path dir) {
        List<String> args =
                new ArrayList<>(
                        List.of(
                                line.substring("weave ".length())
                                        .replace("./demo", dir.resolve("demo").toString())
                                        .split(" ")));
        PrintStream so = System.out;
        PrintStream se = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            int code = WeaveCLI.commandLine().execute(args.toArray(String[]::new));
            return new Ran(
                    code,
                    out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(so);
            System.setErr(se);
        }
    }

    @Test
    void theDocumentedPathWorksWithNoKeyAndNoNetworkInSixCommandsOrFewer() throws Exception {
        List<String> commands = documented();
        assertThat(commands).hasSizeLessThanOrEqualTo(6).hasSizeGreaterThanOrEqualTo(5);
        assertThat(commands.get(0)).isEqualTo("weave try --fast");
        long cpuBefore = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        List<Ran> ran = new ArrayList<>();
        for (String c : commands) {
            Ran r = weave(c, dir);
            ran.add(r);
            assertThat(r.code()).as(c + "\n" + r.out() + r.err()).isZero();
        }
        assertThat(ran.get(0).out())
                .contains("Demo models")
                .contains("weave try triage --keep ./demo");
        assertThat(ran.get(1).out()).contains("Wrote the triage project");
        assertThat(Files.exists(dir.resolve("demo/main.loom"))).isTrue();
        assertThat(ran.get(commands.size() - 1).out()).contains("weave next").contains("Now");
        double cpuSeconds =
                (ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - cpuBefore) / 1e9;
        assertThat(cpuSeconds)
                .as("CPU seconds on this thread for the whole journey")
                .isLessThan(30);
    }

    @Test
    void theNextCommandAfterTheJourneyNamesTheStepsInTheOrderTheGuideSays() throws Exception {
        weave("weave try --keep ./demo", dir);
        Ran next = weave("weave next ./demo", dir);
        int review = next.out().indexOf("weave review");
        int test = next.out().indexOf("weave test");
        int deploy = next.out().indexOf("weave deploy");
        assertThat(review).isPositive();
        assertThat(test).isGreaterThan(review);
        assertThat(deploy).isGreaterThan(test);
    }
}
