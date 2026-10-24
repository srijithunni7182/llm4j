package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.review.Reviews;
import io.github.llm4j.loom.review.cost.Assumptions;
import io.github.llm4j.loom.review.cost.Calibration;
import io.github.llm4j.loom.review.cost.CostEstimator;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.engine.ReviewRule;
import io.github.llm4j.loom.review.engine.RuleCatalog;
import io.github.llm4j.loom.review.engine.RuleInfo;
import io.github.llm4j.loom.review.handle.Baseline;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.pack.JavaRules;
import io.github.llm4j.loom.review.pack.PackException;
import io.github.llm4j.loom.review.pack.PackSet;
import io.github.llm4j.loom.review.report.JsonReport;
import io.github.llm4j.loom.review.report.MarkdownReport;
import io.github.llm4j.loom.review.report.SarifReport;
import io.github.llm4j.loom.review.report.TextReport;
import io.github.llm4j.loom.review.settings.InvalidSettingsException;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave review}: one ranked list of what is wrong or doubtful in a script, across every
 * lens, without running anything. It is advisory: it exits 1 on findings only when asked to with
 * {@code --fail-on} (or {@code fail-on =} in {@code review.properties}).
 */
@Command(
        name = "review",
        description =
                "Reviews a script without running it: security, validity and the other lenses in one ranked list, each finding with why it matters and what to do. Advisory: it exits 1 on findings only when you ask with --fail-on.")
final class ReviewCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "The .loom script to review.")
    File script;

    @Option(
            names = {"-l", "--loot"},
            description =
                    "The .loot tool mapping file: its Name.reach lines say what your own tools reach.")
    File lootFile;

    @Option(
            names = "--format",
            description = "text (default), md, json or sarif (for code scanning).",
            defaultValue = "text")
    String format;

    @Option(
            names = "--lens",
            description =
                    "Which lenses run: 'all', exact names (security,cost), or +name / -name to change the defaults.")
    String lens;

    @Option(
            names = "--min-severity",
            description =
                    "Show findings at least this severe: high, medium, low or info (default).",
            defaultValue = "info")
    String minSeverity;

    @Option(
            names = "--fail-on",
            description =
                    "Exit 1 when a finding is at least this severe: high, medium, low, info or none. Default: none, because review is advisory.")
    String failOn;

    @Option(
            names = "--rules",
            description = "The settings file (default: review.properties beside the script).")
    File rulesFile;

    @Option(
            names = "--no-env",
            description = "Review without keys: names that are not set are not problems.")
    boolean noEnv;

    @Option(
            names = "--rules-jar",
            description =
                    "A jar of Java rules (io.github.llm4j.loom.review.engine.ReviewRule, found with ServiceLoader). Repeatable. Nothing is loaded unless you name it.")
    List<File> rulesJars = new ArrayList<>();

    @Option(
            names = "--prices",
            description =
                    "The price table (lines of 'model = input / output' per million tokens), as for weave run. Without it the cost is shown in tokens.")
    File prices;

    @Option(
            names = "--assume",
            paramLabel = "<name=value>",
            description =
                    "Override a cost assumption, such as loop.iterations=5 (repeatable). weave review --explain cost lists them.")
    List<String> assume = new ArrayList<>();

    @Option(
            names = "--calibrate",
            paramLabel = "<run-dir>",
            description =
                    "Compare the cost estimate with what a journaled run (a folder made with a run store) really spent, and say which assumptions to correct.")
    File calibrate;

    @Option(
            names = "--accept",
            paramLabel = "<fingerprint|RULE>",
            description =
                    "Accept open findings with a reason: one finding by its fingerprint, or every open finding of a rule. Written to review.properties; run and deploy are not affected.")
    String accept;

    @Option(
            names = "--reason",
            description = "Why the finding is accepted (required with --accept).")
    String reason;

    @Option(
            names = "--scope",
            description = "How much --accept covers: finding (default), thing, script or project.",
            defaultValue = "finding")
    String scope;

    @Option(names = "--on", description = "The agent, tool or workflow for --scope thing.")
    String on;

    @Option(
            names = "--until",
            description = "Look at an accepted finding again on this date, such as 2026-12-31.")
    String until;

    @Option(names = "--by", description = "Who accepts it (default: the user name).")
    String by;

    @Option(
            names = "--confirm",
            description = "The number of serious findings --accept is about to accept.")
    Integer confirm;

    @Option(
            names = "--strict-accepts",
            description = "Count accepted findings when deciding --fail-on.")
    boolean strictAccepts;

    @Option(
            names = "--baseline-write",
            description = "Record today's open findings as known in review.baseline.json.")
    boolean baselineWrite;

    @Option(
            names = "--baseline-include-high",
            description = "Let --baseline-write record serious findings too.")
    boolean baselineIncludeHigh;

    @Option(
            names = "--baseline-prune",
            description = "Remove baseline entries whose finding is gone.")
    boolean baselinePrune;

    @Option(
            names = "--baseline-note",
            description = "The note --baseline-write stores.",
            defaultValue = "Existing findings when review was adopted.")
    String baselineNote;

    @Option(
            names = "--baseline",
            description = "The baseline file (default: review.baseline.json beside the script).")
    File baselineFile;

    @Option(
            names = "--noise-report",
            description = "Say which rules are noisy: accepted in many of the places they fire.")
    boolean noiseReport;

    @Option(
            names = "--noise-share",
            description = "Percent of places accepted that makes a rule noisy.",
            defaultValue = "50")
    int noiseShare;

    @Option(
            names = "--fix",
            description = "Apply every safe fix, checking each result, then review again.")
    boolean fix;

    @Option(
            names = "--fix-rule",
            paramLabel = "<RULE>",
            description =
                    "Apply the fixes of one rule, including ones that need your judgement (with --yes).")
    String fixRule;

    @Option(
            names = "--fix-finding",
            paramLabel = "<fingerprint>",
            description = "Apply the fix of one finding (with --yes if it needs your judgement).")
    String fixFinding;

    @Option(
            names = "--assist",
            description =
                    "Go through the findings that have options with you: choose, answer, see the change, confirm.")
    boolean assist;

    @Option(
            names = "--option",
            paramLabel = "<id>",
            description = "With --fix-finding: apply this option of the finding (see its options).")
    String option;

    @Option(
            names = "--set",
            paramLabel = "<name=value>",
            description = "With --option: the answer to one of the option's questions. Repeatable.")
    List<String> answers = new java.util.ArrayList<>();

    @Option(
            names = "--dry-run",
            description =
                    "With a fix option: print the diff and change nothing; exit 1 if fixes are pending.")
    boolean dryRun;

    @Option(names = "--yes", description = "Confirm fixes that need your judgement.")
    boolean yes;

    @Option(names = "--out", description = "Also write the report to this file.")
    File out;

    @Option(
            names = "--explain",
            paramLabel = "<RULE>",
            description = "Explain one rule (such as LA01): what it checks, why, and what to do.")
    String explain;

    @CommandLine.Mixin private EnvFileOptions envFile = new EnvFileOptions();

    @CommandLine.Mixin private ClassesOptions classesOptions = new ClassesOptions();

    @CommandLine.Mixin private ContextOptions contextOptions = new ContextOptions();

    @CommandLine.Mixin private PromptOptions promptOptions = new PromptOptions();

    @Override
    public Integer call() {
        WeaveEnv env = WeaveEnv.system();
        if (script != null) {
            env = envFile.apply(env, script.toPath());
            if (env == null
                    || !classesOptions.apply(script.toPath(), env)
                    || !contextOptions.apply(script.toPath(), env)) return 2;
            env = promptOptions.apply(env);
            if (env == null) return 2;
        }
        return review(this, env);
    }

    /** A review that could not be set up: the lines {@code weave review} prints for it. */
    static final class SetupError extends Exception {
        private final List<String> lines;

        SetupError(List<String> lines) {
            super(String.join("; ", lines));
            this.lines = lines;
        }

        List<String> lines() {
            return lines;
        }
    }

    /**
     * Everything a review needs, set up from the options: the same for the command and for {@code
     * weave serve}.
     */
    record Opened(
            ReviewSession session,
            ReviewSettings settings,
            CostEstimator estimator,
            Path scriptDir,
            Path baselinePath,
            LocalDate today,
            PackSet packs,
            List<ReviewRule> javaRules) {}

    static Opened open(ReviewCommand c, WeaveEnv env) throws SetupError {
        ReviewSettings settings;
        try {
            settings = settings(c);
        } catch (InvalidSettingsException e) {
            throw new SetupError(e.problems().stream().map(p -> "Error: " + p).toList());
        } catch (IllegalArgumentException e) {
            throw new SetupError(List.of("Error: " + e.getMessage()));
        }
        Path scriptDir = c.script.getAbsoluteFile().getParentFile().toPath();
        Path baselinePath =
                c.baselineFile != null
                        ? c.baselineFile.toPath()
                        : scriptDir.resolve(Baseline.FILE_NAME);
        LocalDate today = LocalDate.now(env.clock());
        try {
            settings = settings.withToday(today).withBaseline(Baseline.read(baselinePath));
        } catch (IllegalArgumentException e) {
            throw new SetupError(List.of("Error: " + e.getMessage()));
        }
        try {
            new LoomLoader().load(c.script.getAbsolutePath());
        } catch (Exception e) {
            throw new SetupError(
                    List.of("Error: " + c.script + " could not be read: " + e.getMessage()));
        }
        Map<String, String> reach = Map.of();
        if (c.lootFile != null) {
            if (!c.lootFile.exists())
                throw new SetupError(List.of("Error: " + c.lootFile + " does not exist."));
            List<String> problems =
                    io.github.llm4j.loom.execution.LootLoader.reachProblems(c.lootFile.toPath());
            if (!problems.isEmpty())
                throw new SetupError(problems.stream().map(p -> "Error: " + p).toList());
            reach = io.github.llm4j.loom.execution.LootLoader.reaches(c.lootFile.toPath());
        }
        PackSet packs;
        try {
            packs = Reviews.packs(settings, scriptDir);
        } catch (PackException e) {
            throw new SetupError(e.problems().stream().map(p -> "Error: " + p).toList());
        }
        List<ReviewRule> javaRules;
        try {
            javaRules = JavaRules.fromJars(c.rulesJars.stream().map(File::toPath).toList());
        } catch (PackException e) {
            throw new SetupError(e.problems().stream().map(p -> "Error: " + p).toList());
        }
        CostEstimator estimator;
        try {
            estimator = estimator(c);
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new SetupError(
                    List.of(
                            "Error: the price table "
                                    + c.prices
                                    + " could not be read: "
                                    + e.getMessage()));
        }
        ReviewSession session =
                new ReviewSession(
                        c,
                        env,
                        settings,
                        Reviews.engine(packs, javaRules, estimator),
                        reach,
                        estimator);
        return new Opened(
                session, settings, estimator, scriptDir, baselinePath, today, packs, javaRules);
    }

    static int review(ReviewCommand c, WeaveEnv env) {
        if (!List.of("text", "md", "json", "sarif").contains(c.format)) {
            env.err().println("Error: --format takes text, md, json or sarif.");
            return 2;
        }
        Optional<Severity> min = Severity.parse(c.minSeverity);
        if (min.isEmpty()) {
            env.err().println("Error: --min-severity takes high, medium, low or info.");
            return 2;
        }
        if (c.failOn != null
                && !c.failOn.equalsIgnoreCase("none")
                && Severity.parse(c.failOn).isEmpty()) {
            env.err().println("Error: --fail-on takes high, medium, low, info or none.");
            return 2;
        }
        if (c.explain != null) {
            RuleCatalog catalog = catalogFor(c, env);
            return catalog == null ? 2 : explain(c.explain, catalog, env);
        }
        if (c.script == null) {
            env.err().println("Error: weave review needs a script (or --explain RULE).");
            return 2;
        }
        Opened opened;
        try {
            opened = open(c, env);
        } catch (SetupError e) {
            e.lines().forEach(l -> env.err().println(l));
            return 2;
        }
        ReviewSettings settings = opened.settings();
        ReviewSession session = opened.session();
        CostEstimator estimator = opened.estimator();
        Path scriptDir = opened.scriptDir();
        Path baselinePath = opened.baselinePath();
        LocalDate today = opened.today();
        PackSet packs = opened.packs();
        List<ReviewRule> javaRules = opened.javaRules();
        ReviewReport report;
        try {
            if (c.calibrate != null)
                return calibrate(
                        c, session.input(c.script.toPath(), null), settings, estimator, env);
            report = session.review(c.script.toPath());
        } catch (Exception e) {
            env.err().println("Error: " + c.script + " could not be read: " + e.getMessage());
            return 2;
        }
        if (c.assist || c.option != null) {
            Integer done = ReviewAssist.run(c, session, report, env);
            if (done != null) return done;
            try {
                report = session.review(c.script.toPath());
            } catch (Exception e) {
                env.err().println("Error: " + c.script + " could not be read: " + e.getMessage());
                return 2;
            }
        } else if (c.fix || c.fixRule != null || c.fixFinding != null) {
            Integer done = ReviewFixing.apply(c, session, report, env);
            if (done != null) return done;
            try {
                report = session.review(c.script.toPath());
            } catch (Exception e) {
                env.err().println("Error: " + c.script + " could not be read: " + e.getMessage());
                return 2;
            }
        }
        if (ReviewHandling.requested(c))
            return ReviewHandling.apply(c, report, scriptDir, baselinePath, today, env);
        ReviewReport shown = atLeast(report, min.get());
        String text =
                switch (c.format) {
                    case "json" -> JsonReport.write(shown);
                    case "md" -> MarkdownReport.write(shown);
                    case "sarif" -> SarifReport.write(shown, Reviews.catalog(packs, javaRules));
                    default -> TextReport.write(shown);
                };
        env.out().println(text);
        if (c.out != null) {
            try {
                Files.writeString(c.out.toPath(), text);
            } catch (Exception e) {
                env.err().println("Error: could not write " + c.out + ": " + e.getMessage());
                return 2;
            }
        }
        Optional<Severity> threshold = threshold(c, settings);
        if (threshold.isPresent()
                && (report.failsAt(threshold.get())
                        || c.strictAccepts
                                && report.suppressed().stream()
                                        .anyMatch(
                                                x ->
                                                        x.finding()
                                                                .severity()
                                                                .atLeast(threshold.get()))))
            return 1;
        return 0;
    }

    private static int calibrate(
            ReviewCommand c,
            ReviewInput input,
            ReviewSettings settings,
            CostEstimator estimator,
            WeaveEnv env) {
        try {
            var result =
                    Calibration.compare(c.calibrate.toPath(), estimator.estimate(input, settings));
            env.out().print(Calibration.text(result));
            return 0;
        } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
    }

    private static CostEstimator estimator(ReviewCommand c) throws java.io.IOException {
        if (c.prices == null) return new CostEstimator();
        return new CostEstimator(
                io.github.llm4j.budget.PriceTable.load(c.prices.toPath()), c.prices.getName());
    }

    private static ReviewSettings settings(ReviewCommand c) {
        ReviewSettings s;
        if (c.rulesFile != null) {
            if (!c.rulesFile.isFile())
                throw new IllegalArgumentException(c.rulesFile + " does not exist.");
            s = ReviewSettings.load(c.rulesFile.toPath());
        } else {
            s = ReviewSettings.besides(c.script.toPath());
        }
        if (c.lens != null) s = s.withLenses(c.lens);
        for (String a : c.assume) {
            int eq = a.indexOf('=');
            if (eq < 1)
                throw new IllegalArgumentException(
                        "--assume takes name=value, such as loop.iterations=5, not '" + a + "'");
            String key = a.substring(0, eq).strip();
            s =
                    s.withValue(
                            key.startsWith("assume.") ? key : "assume." + key,
                            a.substring(eq + 1).strip());
        }
        Assumptions.of(s); // refuses an unknown or malformed assumption before anything runs
        return s;
    }

    private static Optional<Severity> threshold(ReviewCommand c, ReviewSettings settings) {
        if (c.failOn == null) return settings.failOn();
        if (c.failOn.equalsIgnoreCase("none")) return Optional.empty();
        return Severity.parse(c.failOn);
    }

    private static ReviewReport atLeast(ReviewReport r, Severity min) {
        return new ReviewReport(
                r.file(),
                r.findings().stream().filter(f -> f.severity().atLeast(min)).toList(),
                r.suppressed(),
                r.notes(),
                r.lensesRun(),
                r.lensesOff(),
                r.cost(),
                r.baselined());
    }

    /**
     * Every rule in force for the script (core, its pinned packs, named jars), or null on error.
     */
    private static RuleCatalog catalogFor(ReviewCommand c, WeaveEnv env) {
        try {
            PackSet packs = PackSet.coreOnly(Reviews.corePack());
            if (c.script != null) {
                ReviewSettings settings = settings(c);
                packs =
                        Reviews.packs(
                                settings, c.script.getAbsoluteFile().getParentFile().toPath());
            }
            return Reviews.catalog(
                    packs, JavaRules.fromJars(c.rulesJars.stream().map(File::toPath).toList()));
        } catch (InvalidSettingsException e) {
            e.problems().forEach(p -> env.err().println("Error: " + p));
        } catch (PackException e) {
            e.problems().forEach(p -> env.err().println("Error: " + p));
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
        }
        return null;
    }

    private static int explainCost(WeaveEnv env) {
        env.out().println("cost  What a workflow is estimated to spend");
        env.out().println();
        env.out()
                .println(
                        "The estimate comes from the script alone: it never calls a model. Typical uses the assumptions below; worst uses the most the script allows (a loop's max, every retry, the larger for each). Where nothing bounds a loop the worst case is reported as unbounded, never as a number. A budget caps the worst case. Models with no price are named, never counted as free.");
        env.out().println();
        env.out()
                .println(
                        "Assumptions (change with assume.<name> in review.properties or --assume name=value):");
        for (Assumptions.Def d : Assumptions.DEFS)
            env.out()
                    .println(
                            String.format(
                                    "  %-28s %-6d %s",
                                    d.key().substring(7), d.defaultValue(), d.meaning()));
        env.out().println();
        env.out()
                .println(
                        "Tokens are counted as the run-time guard counts them: about 4 characters per token plus 10 %.");
        env.out()
                .println(
                        "Two ways of an alt are taken as equally likely for the typical case; the dearer way is the worst case.");
        return 0;
    }

    private static int explain(String id, RuleCatalog catalog, WeaveEnv env) {
        if (id.equalsIgnoreCase("cost")) return explainCost(env);
        Optional<RuleInfo> info = catalog.find(id.toUpperCase(Locale.ROOT));
        if (info.isEmpty()) {
            env.err()
                    .println(
                            "Error: no rule "
                                    + id
                                    + catalog.hint(id.toUpperCase(Locale.ROOT))
                                    + ". weave review --explain takes a rule id such as LA01.");
            return 2;
        }
        RuleInfo r = info.get();
        env.out().println(r.id() + "  " + r.title());
        String severity = r.severity() == null ? "" : "   Severity: " + r.severity().word();
        env.out().println("Lens: " + r.lens().title() + severity);
        env.out().println("From: " + r.source());
        env.out().println();
        env.out().println(r.explain());
        return 0;
    }
}
