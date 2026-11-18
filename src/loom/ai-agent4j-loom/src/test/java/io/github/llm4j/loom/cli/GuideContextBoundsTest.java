package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 37, review of PR 131: the summary's word limit is kept in every case. When the free texts are not enough, the
 * stages' longer texts go next, then the long lists; every cut is named, and a summary that fits is never touched.
 */
class GuideContextBoundsTest {
    @TempDir Path dir;

    private static WeaveEnv quiet() {
        PrintStream sink = new PrintStream(new ByteArrayOutputStream());
        return WeaveEnv.system().withStreams(sink, sink).withEnvLookup(k -> null);
    }

    private static Map<String, Object> row(String name, int words) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("text", "word ".repeat(words));
        r.put("count", 3);
        r.put("short", "ok");
        return r;
    }

    private static List<Object> rows(String prefix, int n, int words) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(row(prefix + i, words));
        return out;
    }

    @Test
    void everyStepIsTakenWhenTheSummaryIsStillTooLong() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> journey = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("id", "s" + i);
            st.put("state", "done");
            for (String k : List.of("why", "see", "decision", "evidence")) st.put(k, "long ".repeat(300));
            st.put("missing", "short");
            journey.add(st);
        }
        m.put("journey", journey);
        m.put("skips", rows("skip", 3, 10));
        m.put("warnings", new ArrayList<>(List.of("w".repeat(400), "short warning", 7)));
        m.put("agents", rows("agent", 40, 60));
        m.put("tools", rows("tool", 40, 60));
        m.put("moments", rows("moment", 40, 60));
        m.put("findings", new LinkedHashMap<>(Map.of("high", 0, "top", rows("finding", 20, 60))));

        Map<String, Object> cut = GuideContext.bounded(m);

        assertThat(GuideContext.words(GuideContextCommand.json(cut))).isLessThanOrEqualTo(GuideContext.MAX_WORDS);
        @SuppressWarnings("unchecked")
        Map<String, Object> stage = (Map<String, Object>) ((List<Object>) cut.get("journey")).get(0);
        assertThat(stage).containsKeys("id", "state", "missing").doesNotContainKeys("why", "see", "decision", "evidence");
        assertThat(String.valueOf(cut.get("cut")))
                .contains("the stages' why, see, decision and evidence")
                .contains("35 more agents").contains("35 more tools").contains("35 more moments").contains("15 more findings");
        assertThat(cut.get("warnings").toString()).contains("short warning").contains("7").contains("…");
        assertThat(cut.get("agents").toString()).contains("count=3").contains("short=ok");
    }

    @Test
    void findingsWithNoListAreLeftAsTheyAre() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("journey", List.of());
        m.put("skips", List.of());
        m.put("warnings", List.of());
        m.put("agents", rows("agent", 40, 80));
        m.put("tools", List.of());
        m.put("moments", List.of());
        m.put("findings", Map.of("note", "the audit did not run"));

        Map<String, Object> cut = GuideContext.bounded(m);

        assertThat(cut.get("findings")).isEqualTo(Map.of("note", "the audit did not run"));
        assertThat(GuideContext.words(GuideContextCommand.json(cut))).isLessThanOrEqualTo(GuideContext.MAX_WORDS);
    }

    @Test
    void aBudgetWithOnlyACostShowsOnlyTheCost() throws Exception {
        Path p = dir.resolve("e");
        assertThat(WeaveCLI.commandLine().execute("init", "empty", p.toString(), "--flat")).isZero();
        Path script = p.resolve("main.loom");
        Files.writeString(script, Files.readString(script).replace("budget { tokens: 50000  calls: 10 }", "budget { cost: \"$2.00\" }"));

        Object budget = GuideContext.of(script, quiet()).get("budget");

        assertThat(budget).isEqualTo(Map.of("cost", "2.00"));
    }

    @Test
    void theTestsAndRealRunTriggersFollowTheProject() throws Exception {
        Path p = dir.resolve("e");
        assertThat(WeaveCLI.commandLine().execute("init", "empty", p.toString(), "--flat")).isZero();
        Path script = p.resolve("main.loom");
        try (var walk = Files.walk(p.resolve("tests"))) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        }
        assertThat(GuideContext.of(script, quiet()).get("triggers").toString()).contains("no-tests").contains("no-real-run");

        RealRunRead.confirm(p, script, java.time.Clock.systemUTC());

        assertThat(GuideContext.of(script, quiet()).get("triggers").toString()).doesNotContain("no-real-run");
    }
}
