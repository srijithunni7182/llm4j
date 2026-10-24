package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** A11 of spec 09: a warm review of a script of 300 lines takes under a second. */
class ReviewPerformanceTest {

    @TempDir Path dir;

    static String script(int lines) {
        StringBuilder b = new StringBuilder("tool Mail { use: webhook  url: env.HOOK }\n");
        int agents = (lines - 6) / 2;
        for (int i = 0; i < agents; i++)
            b.append("agent A")
                    .append(i)
                    .append(" { model: \"m\"  system: \"You help with task ")
                    .append(i)
                    .append(".\"  tools: [Mail] }\n");
        b.append("workflow Main(q) {\n");
        for (int i = 0; i < agents; i++)
            b.append("  delegate \"task ")
                    .append(i)
                    .append(": {q}\" to A")
                    .append(i)
                    .append(" -> r")
                    .append(i)
                    .append("\n");
        b.append("  note \"done\"\n}\n");
        return b.toString();
    }

    long review(Path file) throws Exception {
        ReviewCommand c = new ReviewCommand();
        new CommandLine(c)
                .parseArgs(new String[] {file.toString(), "--format", "json", "--lens", "all"});
        WeaveEnv env =
                new WeaveEnv(
                        m -> null,
                        q -> "",
                        new PrintStream(new ByteArrayOutputStream(), true),
                        new PrintStream(new ByteArrayOutputStream(), true),
                        Clock.systemUTC(),
                        d -> {},
                        cmd ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> k.equals("HOOK") ? "https://example.com/hook" : null);
        long start = System.nanoTime();
        assertThat(ReviewCommand.review(c, env)).isZero();
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    void aWarmReviewOfA300LineScriptWithEveryLensTakesUnderASecond() throws Exception {
        Path file = dir.resolve("big.loom");
        String text = script(300);
        assertThat(text.lines().count()).isBetween(250L, 350L);
        Files.writeString(file, text);
        review(file); // cold: classes load
        review(file);
        long best = Math.min(review(file), Math.min(review(file), review(file)));
        System.out.println(
                "warm review of " + text.lines().count() + " lines, all lenses: " + best + " ms");
        assertThat(best).as("milliseconds, best of three warm runs").isLessThan(1000);
    }
}
