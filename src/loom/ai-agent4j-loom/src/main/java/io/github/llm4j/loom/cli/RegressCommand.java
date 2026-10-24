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
            description =
                    "recorded (default): only recorded answers; live: ask a real model for the runs whose question changed (needs --max-cost or --max-tokens).",
            defaultValue = "recorded")
    String models;

    @Option(
            names = "--max-cost",
            description =
                    "The most live models and judges may cost, in dollars, in all (needs --prices).")
    java.math.BigDecimal maxCost;

    @Option(
            names = "--max-tokens",
            description = "The most live models and judges may use, in tokens, in all.")
    Long maxTokens;

    @Option(
            names = "--prices",
            description = "A price file (model = in / out per million tokens), to count money.")
    File prices;

    @Option(
            names = "--cases",
            description =
                    "A folder of captured cases; the approved ones are always run against the candidate.")
    List<File> cases = new ArrayList<>();

    @Option(
            names = "--judge-model",
            description = "The model that judges text with rubric: (classifier: uses its own).")
    String judgeModel;

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
            description =
                    "flip-unsafe (default), flip-any, cost-up:<percent> or none: exit 1 on this.",
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
            if (!Set.of("flip-unsafe", "flip-any", "none").contains(c.failOn)
                    && !c.failOn.matches("cost-up:\\d+(\\.\\d+)?")) {
                env.err()
                        .println(
                                "Error: --fail-on takes flip-unsafe, flip-any, cost-up:<percent> or none.");
                return 1;
            }
            boolean modelJudge = c.judge.startsWith("rubric:") || c.judge.startsWith("classifier:");
            if (!Set.of("none", "exact", "label").contains(c.judge) && !modelJudge) {
                env.err()
                        .println(
                                "Error: --judge takes none, exact, label, rubric:<file> or classifier:<name>.");
                return 1;
            }
            if (!Set.of("recorded", "live").contains(c.models)) {
                env.err().println("Error: --models takes recorded or live.");
                return 1;
            }
            boolean spends = c.models.equals("live") || modelJudge;
            if (spends && c.maxCost == null && c.maxTokens == null) {
                env.err()
                        .println(
                                "Error: "
                                        + (modelJudge ? "--judge " + c.judge : "--models live")
                                        + " calls a model, which costs money: give --max-cost (with --prices) or --max-tokens.");
                return 1;
            }
            if (spends && c.maxCost != null && c.prices == null) {
                env.err()
                        .println(
                                "Error: --max-cost counts money, so it needs --prices (or use --max-tokens).");
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
                io.github.llm4j.budget.PriceTable table =
                        c.prices == null
                                ? null
                                : io.github.llm4j.budget.PriceTable.load(c.prices.toPath());
                io.github.llm4j.loom.loop.Spent spent =
                        spends ? new io.github.llm4j.loom.loop.Spent(c.maxTokens, c.maxCost) : null;
                Regress.Live live =
                        c.models.equals("live")
                                ? new Regress.Live(env.models(), table, env.env(), spent)
                                : null;
                io.github.llm4j.loom.loop.Judges.Judge textJudge =
                        judge(c, env, cand, spent, table);
                Regress.Costing costing = script -> costOf(env, script, c.prices);
                List<Path> caseDirs = new ArrayList<>();
                for (File f : c.cases) caseDirs.add(f.toPath());
                if (caseDirs.isEmpty()) caseDirs.add(cand.getParent().resolve("tests/captured"));
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
                                        c.perRun,
                                        live,
                                        textJudge,
                                        costing,
                                        caseDirs));
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
            if (r.stoppedByCap()) return 4;
            return failing(c.failOn, r) ? 1 : 0;
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            env.err().println("Error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return 2;
        }
    }

    private static io.github.llm4j.loom.loop.Judges.Judge judge(
            RegressCommand c,
            WeaveEnv env,
            Path candidate,
            io.github.llm4j.loom.loop.Spent spent,
            io.github.llm4j.budget.PriceTable table)
            throws Exception {
        if (c.judge.startsWith("rubric:")) {
            String rubric = Files.readString(Path.of(c.judge.substring(7)), StandardCharsets.UTF_8);
            String model = judgeModelOf(c);
            return io.github.llm4j.loom.loop.Judges.rubric(
                    env.models().createClient(model), model, rubric, spent, table);
        }
        if (c.judge.startsWith("classifier:")) {
            String name = c.judge.substring(11);
            var script = new io.github.llm4j.loom.execution.LoomLoader().load(candidate.toString());
            var def = script.findIntentClassifier(name);
            if (def == null)
                throw new IllegalArgumentException(
                        "--judge classifier:"
                                + name
                                + ": the candidate has no such intentclassifier.");
            String model = c.judgeModel != null ? c.judgeModel : def.getModel();
            return io.github.llm4j.loom.loop.Judges.classifier(
                    env.models().createClient(model), def, spent, table);
        }
        return null;
    }

    private static String judgeModelOf(RegressCommand c) {
        if (c.judgeModel == null || c.judgeModel.isBlank())
            throw new IllegalArgumentException(
                    "--judge rubric: needs --judge-model, the model that judges.");
        return c.judgeModel;
    }

    /** The review's estimate of what a typical run of each workflow costs, in tokens and money. */
    private static Map<String, Regress.CostFigure> costOf(WeaveEnv env, Path script, File prices)
            throws Exception {
        var snapshot = GenerateReview.snapshotter(env, prices).snapshot(script);
        Map<String, Regress.CostFigure> out = new LinkedHashMap<>();
        snapshot.cost()
                .forEach(
                        (name, t) ->
                                out.put(
                                        name,
                                        new Regress.CostFigure(t.typical(), t.typicalCost())));
        return out;
    }

    static boolean failing(String failOn, Regress.Report r) {
        if (!r.approvedFlips().isEmpty() && !failOn.equals("none")) return true;
        if (failOn.startsWith("cost-up:"))
            return RegressReport.costUp(r) > Double.parseDouble(failOn.substring(8));
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
