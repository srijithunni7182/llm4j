package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** R35 and R36: the time and effects estimates reach the reports. */
class TimeEffectsReportTest {

    @TempDir Path dir;

    ByteArrayOutputStream out = new ByteArrayOutputStream();

    static final String SRC =
            "tool Mail { use: webhook  url: env.HOOK }\n"
                    + "agent N { model: \"m\"  system: \"s\"  tools: [Mail] }\n"
                    + "workflow Main(q) {\n  delegate \"tell {q}\" to N -> sent\n  human_prompt \"ok?\" -> ok\n}\n";

    int run(String... args) throws Exception {
        Path f = dir.resolve("w.loom");
        Files.writeString(f, SRC);
        ReviewCommand c = new ReviewCommand();
        List<String> all = new ArrayList<>(List.of(f.toString()));
        all.addAll(List.of(args));
        new CommandLine(c).parseArgs(all.toArray(new String[0]));
        WeaveEnv env =
                new WeaveEnv(
                        m -> null,
                        q -> "",
                        new PrintStream(out, true),
                        new PrintStream(new ByteArrayOutputStream(), true),
                        Clock.systemUTC(),
                        d -> {},
                        cmd ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> k.equals("HOOK") ? "https://example.com/hook" : null);
        return ReviewCommand.review(c, env);
    }

    @Test
    void theJsonCostBlockCarriesTimeAndEffects() throws Exception {
        assertThat(run("--format", "json")).isZero();
        JsonNode w =
                new ObjectMapper().readTree(out.toString()).get("cost").get("workflows").get(0);
        assertThat(w.get("time").get("typicalMs").asLong()).isEqualTo(21000);
        assertThat(w.get("time").get("worst").get("ms").asLong()).isEqualTo(70000);
        assertThat(w.get("time").get("waits").get(0).asText()).startsWith("human_prompt");
        assertThat(w.get("effects").get("sends").get("worst").asLong()).isEqualTo(10);
        assertThat(w.get("effects").get("writes").get("typical").asLong()).isZero();
    }

    @Test
    void theTextReportSaysItInWords() throws Exception {
        assertThat(run()).isZero();
        assertThat(out.toString())
                .contains("time typical 21 s, worst 70 s (+ waits: 1 stop)")
                .contains("effects worst 10 sends (typically 1)");
    }

    private JsonNode rules(String settings) throws Exception {
        Files.writeString(dir.resolve("review.properties"), settings);
        out.reset();
        assertThat(run("--format", "json", "--min-severity", "info")).isZero();
        return new ObjectMapper().readTree(out.toString()).get("findings");
    }

    private static boolean has(JsonNode findings, String rule) {
        for (JsonNode f : findings) if (f.get("rule").asText().equals(rule)) return true;
        return false;
    }

    @Test
    void lc09SpeaksOnlyWhenTheProjectDeclaresALimitAndTheWorstCaseIsOverIt() throws Exception {
        assertThat(has(rules(""), "LC09")).isFalse();
        assertThat(has(rules("cost.latency.limit = 120\n"), "LC09")).isFalse(); // worst is 70 s
        JsonNode f = rules("cost.latency.limit = 30\n");
        assertThat(has(f, "LC09")).isTrue();
    }

    @Test
    void lc10NeedsTheWorkflowToBeDeclaredInteractive() throws Exception {
        assertThat(has(rules(""), "LC10")).isFalse();
        JsonNode f = rules("workflow.Main.interactive = true\n");
        assertThat(has(f, "LC10")).isTrue(); // the human_prompt, and 21 s typical > 8 s
        long n = 0;
        for (JsonNode x : f) if (x.get("rule").asText().equals("LC10")) n++;
        assertThat(n).isEqualTo(2);
        assertThat(
                        has(
                                rules(
                                        "workflow.Main.interactive = true\ncost.interactive.target = 60\n"),
                                "LC10"))
                .isTrue(); // still the wait on the main path
    }

    @Test
    void lc08FindsTwoIndependentDelegatesAndNamesTheSaving() throws Exception {
        Files.writeString(
                dir.resolve("w.loom"),
                """
                agent A { model: "m"  system: "s" }
                agent B { model: "m"  system: "s" }
                workflow Main(t) {
                  delegate "one {t}" to A -> x
                  delegate "two {t}" to B -> y
                  note "{x} {y}"
                }
                """);
        ReviewCommand c = new ReviewCommand();
        new CommandLine(c)
                .parseArgs(
                        new String[] {
                            dir.resolve("w.loom").toString(),
                            "--format",
                            "json",
                            "--min-severity",
                            "info"
                        });
        out.reset();
        WeaveEnv env =
                new WeaveEnv(
                        m -> null,
                        q -> "",
                        new PrintStream(out, true),
                        new PrintStream(new ByteArrayOutputStream(), true),
                        Clock.systemUTC(),
                        d -> {},
                        cmd ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> null);
        assertThat(ReviewCommand.review(c, env)).isZero();
        JsonNode findings = new ObjectMapper().readTree(out.toString()).get("findings");
        JsonNode lc08 = null;
        for (JsonNode f : findings) if (f.get("rule").asText().equals("LC08")) lc08 = f;
        assertThat(lc08).isNotNull();
        assertThat(lc08.get("title").asText()).contains("saves about 5 s");
    }
}
