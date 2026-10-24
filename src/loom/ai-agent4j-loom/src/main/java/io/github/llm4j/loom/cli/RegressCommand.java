package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.loop.Baselines;
import io.github.llm4j.loom.loop.Candidates;
import io.github.llm4j.loom.loop.Masker;
import io.github.llm4j.loom.loop.Regress;
import io.github.llm4j.loom.loop.RegressReport;
import io.github.llm4j.loom.loop.Sampler;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave regress}: a candidate script, prompt or model tried on real history. Each sampled
 * run is replayed from its journal with the candidate: no model, no tool, no effect, and the run
 * store is only read. It says what changes, unsafe changes first, and what it could not compare.
 *
 * <p>Exit codes: 0 nothing at or above {@code --fail-on}, 1 a failing condition, 2 could not run.
 */
@Command(
        name = "regress",
        description =
                "Tries a candidate script, prompt or model on a sample of real past runs and reports what changes, unsafe changes first. Uses recorded answers only: no model, no tool, no effect; the run store is only read.")
final class RegressCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<candidate.loom>", description = "The script to try.")
    File candidate;

    @Option(names = "--store", description = "Where past runs are kept (default: .loom-triggers).")
    File store;

    @Option(
            names = "--baseline",
            description =
                    "The script that produced the runs (default: the version each run recorded, found in git history).")
    File baseline;

    @Option(names = "--baseline-git", description = "Take the baseline from this git revision.")
    String baselineGit;

    @Option(names = "--since", description = "Only runs this recent (14d, 12h, or a date).")
    String since;

    @Option(
            names = "--limit",
            description = "Most runs to compare (default 200).",
            defaultValue = "200")
    int limit;

    @Option(
            names = "--sample",
            description = "random, recent, stratified (default) or failures-first.",
            defaultValue = "stratified")
    String sample;

    @Option(
            names = "--seed",
            description = "The seed of the sample (default 7).",
            defaultValue = "7")
    long seed;

    @Option(
            names = "--models",
            description = "recorded (the only mode so far).",
            defaultValue = "recorded")
    String models;

    @Option(
            names = "--prompt",
            description = "Try a prompt: Agent=<file> or Agent=@version (repeatable).")
    Map<String, String> prompts = new LinkedHashMap<>();

    @Option(names = "--model", description = "Try a model: Agent=<model id> (repeatable).")
    Map<String, String> modelEdits = new LinkedHashMap<>();

    @Option(
            names = "--judge",
            description =
                    "How free text is compared: none (default; only typed results), exact or label.",
            defaultValue = "none")
    String judge;

    @Option(
            names = "--fail-on",
            description = "flip-unsafe (default), flip-any or none: exit 1 on this.",
            defaultValue = "flip-unsafe")
    String failOn;

    @Option(
            names = "--format",
            description = "text (default), json, md or junit.",
            defaultValue = "text")
    String format;

    @Option(names = "--report", description = "Also write a single self-contained HTML file.")
    File report;

    @Option(names = "--show-text", description = "Include the (masked) result text of unsafe runs.")
    boolean showText;

    @Option(
            names = "--mask-term",
            description = "A name to mask in --show-text output (repeatable).")
    List<String> maskTerm = new ArrayList<>();

    @Option(
            names = "--per-run-seconds",
            description = "Give up on one run after this long (default 30).",
            defaultValue = "30")
    long perRun;

    @Override
    public Integer call() {
        return regress(this, WeaveEnv.system());
    }

    static int regress(RegressCommand c, WeaveEnv env) {
        try {
            if (!Set.of("text", "json", "md", "junit").contains(c.format)) {
                env.err().println("Error: --format takes text, json, md or junit.");
                return 1;
            }
            if (!Set.of("flip-unsafe", "flip-any", "none").contains(c.failOn)) {
                env.err().println("Error: --fail-on takes flip-unsafe, flip-any or none.");
                return 1;
            }
            if (!Set.of("none", "exact", "label").contains(c.judge)) {
                env.err().println("Error: --judge takes none, exact or label.");
                return 1;
            }
            if (!c.models.equals("recorded")) {
                env.err().println("Error: --models takes recorded.");
                return 1;
            }
            Path cand = c.candidate.toPath().toAbsolutePath().normalize();
            if (!Files.isRegularFile(cand)) {
                env.err().println("Error: no script at " + cand + ".");
                return 2;
            }
            Path store =
                    (c.store != null ? c.store.toPath() : Path.of(".loom-triggers"))
                            .toAbsolutePath();
            if (!Files.isDirectory(store)) {
                env.err().println("Error: no run store at " + store + " (--store).");
                return 2;
            }
            try {
                new io.github.llm4j.loom.execution.LoomLoader().load(cand.toString());
            } catch (Exception e) {
                env.err().println("Error: the candidate does not load: " + e.getMessage());
                return 2;
            }
            Map<String, String> prompts = new LinkedHashMap<>();
            for (var e : c.prompts.entrySet()) {
                String v = e.getValue();
                prompts.put(
                        e.getKey(),
                        v.startsWith("@")
                                ? v
                                : Files.readString(Path.of(v), StandardCharsets.UTF_8));
            }
            Path temp = Files.createTempDirectory("weave-regress-");
            Regress.Report r;
            try {
                Baselines baselines =
                        Baselines.of(
                                cand,
                                c.baseline == null ? null : c.baseline.toPath(),
                                c.baselineGit,
                                temp);
                r =
                        Regress.run(
                                new Regress.Request(
                                        cand,
                                        baselines,
                                        store,
                                        Candidates.since(c.since, Instant.now()),
                                        c.limit,
                                        Sampler.Strategy.of(c.sample),
                                        c.seed,
                                        c.judge,
                                        prompts,
                                        c.modelEdits,
                                        c.perRun));
            } finally {
                deleteTree(temp);
            }
            if (r.sample().population() == 0) {
                env.err()
                        .println(
                                "Error: no past run in "
                                        + store
                                        + " can be compared with this candidate"
                                        + (r.excluded().isEmpty()
                                                ? "."
                                                : " ("
                                                        + r.excluded().size()
                                                        + " left out: "
                                                        + r.excluded().get(0).why()
                                                        + ")."));
                return 2;
            }
            Masker masker = new Masker(c.maskTerm);
            switch (c.format) {
                case "json" -> env.out().print(RegressReport.json(r, c.showText, masker));
                case "md" -> env.out().print(RegressReport.markdown(r));
                case "junit" -> env.out().print(RegressReport.junit(r));
                default -> env.out().print(RegressReport.text(r, c.showText, masker));
            }
            if (c.report != null)
                Files.writeString(c.report.toPath(), RegressReport.html(r), StandardCharsets.UTF_8);
            return failing(c.failOn, r) ? 1 : 0;
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            env.err().println("Error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return 2;
        }
    }

    static boolean failing(String failOn, Regress.Report r) {
        return switch (failOn) {
            case "flip-unsafe" -> !r.unsafe().isEmpty();
            case "flip-any" -> r.comparisons().stream()
                    .anyMatch(
                            x ->
                                    x.verdict() == Regress.Verdict.CHANGED_ROUTE
                                            || x.verdict() == Regress.Verdict.CHANGED_RESULT
                                            || x.verdict() == Regress.Verdict.DID_NOT_FINISH
                                            || x.isUnsafe());
            default -> false;
        };
    }

    private static void deleteTree(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
            // a temporary folder that will not go is not worth failing over
        }
    }
}
