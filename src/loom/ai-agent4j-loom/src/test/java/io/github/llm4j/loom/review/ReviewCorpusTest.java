package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.graph.GraphService;
import io.github.llm4j.loom.review.engine.FindingSource;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.pack.DataRuleSource;
import io.github.llm4j.loom.review.rule.RuleDoc;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * R18, R30 of spec 09 (§10): every rule of the core pack is measured on a corpus of labelled
 * scripts. A script marks what it expects with {@code // <- RULE[:severity]} on the line the
 * finding is about, {@code // expect-script: RULE} for a finding about the whole script, and {@code
 * // settings: key=value;key=value} on its first line for declared facts. A script with no marks
 * must produce no finding at all. Precision and recall per rule must be at least 0.9; the table is
 * written to {@code target/review-corpus.md} and copied into VERIFICATION.md.
 *
 * <p>The scripts of the repository's own examples are reviewed as well, and the number of findings
 * each rule gives them is reported (not gated: nobody labelled those) so noise is visible.
 */
class ReviewCorpusTest {

    static final Path CORPUS = Path.of("src/test/resources/review/corpus");
    static final Path EXAMPLES = Path.of("../../examples");
    static final double GATE = 0.9;

    record Mark(String rule, int line, String severity) {}

    record Result(Path file, List<Mark> expected, List<Mark> found) {}

    static final Pattern ARROW = Pattern.compile("//\\s*<-\\s*(.*)$");
    static final Pattern SCRIPT = Pattern.compile("//\\s*expect-script:\\s*(.*)$");
    static final Pattern IGNORE = Pattern.compile("//\\s*ignore:\\s*(.*)$");

    @Test
    void everyRuleMeetsThePrecisionAndRecallGateOnTheLabelledCorpus() throws Exception {
        List<Result> results = new ArrayList<>();
        try (Stream<Path> files = Files.list(CORPUS)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".loom")).sorted().toList())
                results.add(run(f));
        }
        assertThat(results.size())
                .as("the corpus has at least 40 scripts")
                .isGreaterThanOrEqualTo(40);

        Map<String, int[]> tally = new TreeMap<>(); // tp, fp, fn
        List<String> mismatches = new ArrayList<>();
        for (Result r : results) {
            List<Mark> missing = new ArrayList<>(r.expected());
            for (Mark got : r.found()) {
                Mark hit = take(missing, got);
                int[] t = tally.computeIfAbsent(got.rule(), k -> new int[3]);
                if (hit != null) t[0]++;
                else {
                    t[1]++;
                    mismatches.add(r.file().getFileName() + ": unexpected " + got);
                }
            }
            for (Mark m : missing) {
                tally.computeIfAbsent(m.rule(), k -> new int[3])[2]++;
                mismatches.add(r.file().getFileName() + ": expected but not found " + m);
            }
        }

        List<String> coreRules =
                Reviews.corePack().rules().stream().map(RuleDoc::id).sorted().toList();
        StringBuilder table =
                new StringBuilder(
                        "| Rule | Found right | Wrong | Missed | Precision | Recall |\n|---|---|---|---|---|---|\n");
        List<String> failed = new ArrayList<>();
        for (String id : coreRules) {
            int[] t = tally.getOrDefault(id, new int[3]);
            double p = t[0] + t[1] == 0 ? 1 : (double) t[0] / (t[0] + t[1]);
            double rc = t[0] + t[2] == 0 ? 1 : (double) t[0] / (t[0] + t[2]);
            table.append(
                    String.format(
                            "| %s | %d | %d | %d | %.2f | %.2f |%n", id, t[0], t[1], t[2], p, rc));
            if (t[0] + t[2] == 0) failed.add(id + " has no labelled positive in the corpus");
            if (p < GATE) failed.add(id + " precision " + p);
            if (rc < GATE) failed.add(id + " recall " + rc);
        }
        table.append("\nScripts: ")
                .append(results.size())
                .append(". Mismatches: ")
                .append(mismatches.size())
                .append("\n");
        write("review-corpus.md", table.toString());
        assertThat(failed).as(String.join("\n", mismatches)).isEmpty();
        assertThat(mismatches).as("every label agrees with the engine").isEmpty();
    }

    @Test
    void theRepositorysOwnExamplesAreReviewedWithoutFailureAndTheirNoiseIsReported()
            throws Exception {
        if (!Files.isDirectory(EXAMPLES)) return;
        Map<String, Integer> perRule = new TreeMap<>();
        int scripts = 0;
        int skipped = 0;
        try (Stream<Path> files = Files.walk(EXAMPLES)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".loom")).sorted().toList()) {
                try {
                    for (Mark m :
                            review(
                                    f,
                                    ReviewSettings.defaults()
                                            .withLenses("all")
                                            .withHeuristics(true)))
                        perRule.merge(m.rule(), 1, Integer::sum);
                    scripts++;
                } catch (Exception e) {
                    skipped++; // a script that does not load on its own (an import fragment)
                }
            }
        }
        StringBuilder b =
                new StringBuilder(
                        "Repository examples reviewed: "
                                + scripts
                                + " (skipped "
                                + skipped
                                + ")\n\n| Rule | Findings |\n|---|---|\n");
        perRule.forEach((k, v) -> b.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        write("review-examples-noise.md", b.toString());
        assertThat(scripts).isGreaterThan(5);
    }

    private static Mark take(List<Mark> pool, Mark got) {
        for (Mark m : pool)
            if (m.rule().equals(got.rule())
                    && m.line() == got.line()
                    && (m.severity() == null || m.severity().equals(got.severity()))) {
                pool.remove(m);
                return m;
            }
        return null;
    }

    static Result run(Path file) throws Exception {
        String text = Files.readString(file);
        ReviewSettings settings = ReviewSettings.defaults().withLenses("all").withHeuristics(true);
        List<Mark> expected = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i == 0 && line.startsWith("// settings:"))
                for (String kv : line.substring("// settings:".length()).split(";")) {
                    int eq = kv.indexOf('=');
                    settings =
                            settings.withValue(
                                    kv.substring(0, eq).strip(), kv.substring(eq + 1).strip());
                }
            Matcher a = ARROW.matcher(line);
            if (a.find())
                for (String tok : a.group(1).strip().split("\\s+")) {
                    String[] p = tok.split(":");
                    expected.add(new Mark(p[0], i + 1, p.length > 1 ? p[1] : null));
                }
            Matcher s = SCRIPT.matcher(line);
            if (s.find())
                for (String tok : s.group(1).strip().split("\\s+"))
                    expected.add(new Mark(tok, 0, null));
        }
        // rules a script lists after "ignore:" are not under test in it: what they find there is
        // neither counted right nor wrong
        java.util.Set<String> ignored = new java.util.HashSet<>();
        for (String line : lines) {
            Matcher g = IGNORE.matcher(line);
            if (g.find()) ignored.addAll(List.of(g.group(1).strip().split("\\s+")));
        }
        List<Mark> found = new ArrayList<>(review(file, settings));
        found.removeIf(m -> ignored.contains(m.rule()));
        return new Result(file, expected, found);
    }

    static List<Mark> review(Path file, ReviewSettings settings) throws Exception {
        LoomScript script = new LoomLoader().load(file.toString());
        ReviewInput input =
                new ReviewInput(
                        file.getFileName().toString(),
                        file.toAbsolutePath().getParent(),
                        script,
                        Map.of(),
                        List.of(),
                        new GraphService().graph(file));
        List<Mark> out = new ArrayList<>();
        new DataRuleSource(List.of(Reviews.corePack()))
                .collect(
                        input,
                        settings,
                        new FindingSource.Sink() {
                            @Override
                            public void finding(Finding f) {
                                out.add(new Mark(f.rule(), f.where().line(), f.severity().word()));
                            }

                            @Override
                            public void note(Note n) {}
                        });
        return out;
    }

    private static void write(String name, String text) throws IOException {
        Path dir = Path.of("target");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), text);
    }
}
