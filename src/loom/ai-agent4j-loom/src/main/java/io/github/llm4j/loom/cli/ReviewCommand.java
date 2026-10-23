package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.graph.GraphResult;
import io.github.llm4j.loom.graph.GraphService;
import io.github.llm4j.loom.review.Reviews;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.engine.ReviewRule;
import io.github.llm4j.loom.review.engine.RuleCatalog;
import io.github.llm4j.loom.review.engine.RuleInfo;
import io.github.llm4j.loom.review.engine.ValidityProblem;
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
        ReviewSettings settings;
        try {
            settings = settings(c);
        } catch (InvalidSettingsException e) {
            e.problems().forEach(p -> env.err().println("Error: " + p));
            return 2;
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
        LoomScript loaded;
        try {
            loaded = new LoomLoader().load(c.script.getAbsolutePath());
        } catch (Exception e) {
            env.err().println("Error: " + c.script + " could not be read: " + e.getMessage());
            return 2;
        }
        Map<String, String> reach = Map.of();
        if (c.lootFile != null) {
            if (!c.lootFile.exists()) {
                env.err().println("Error: " + c.lootFile + " does not exist.");
                return 2;
            }
            List<String> problems =
                    io.github.llm4j.loom.execution.LootLoader.reachProblems(c.lootFile.toPath());
            if (!problems.isEmpty()) {
                problems.forEach(p -> env.err().println("Error: " + p));
                return 2;
            }
            reach = io.github.llm4j.loom.execution.LootLoader.reaches(c.lootFile.toPath());
        }
        ReviewInput input =
                new ReviewInput(
                        c.script.getName(),
                        c.script.getAbsoluteFile().getParentFile().toPath(),
                        loaded,
                        reach,
                        validity(c, env, loaded),
                        graph(c));
        PackSet packs;
        try {
            packs = Reviews.packs(settings, input.baseDir());
        } catch (PackException e) {
            e.problems().forEach(p -> env.err().println("Error: " + p));
            return 2;
        }
        List<ReviewRule> javaRules;
        try {
            javaRules = JavaRules.fromJars(c.rulesJars.stream().map(File::toPath).toList());
        } catch (PackException e) {
            e.problems().forEach(p -> env.err().println("Error: " + p));
            return 2;
        }
        ReviewReport report = Reviews.engine(packs, javaRules).review(input, settings);
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
        if (threshold.isPresent() && report.failsAt(threshold.get())) return 1;
        return 0;
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
        return s;
    }

    private static Optional<Severity> threshold(ReviewCommand c, ReviewSettings settings) {
        if (c.failOn == null) return settings.failOn();
        if (c.failOn.equalsIgnoreCase("none")) return Optional.empty();
        return Severity.parse(c.failOn);
    }

    /** What weave check reports, as problems the review can read without the validator. */
    private static List<ValidityProblem> validity(
            ReviewCommand c, WeaveEnv env, LoomScript loaded) {
        WeaveCLI.Validation v =
                WeaveCLI.validate(
                        c.script,
                        c.lootFile,
                        new WeaveCLI.CheckSettings(false, false, c.noEnv, false),
                        env,
                        loaded);
        List<ValidityProblem> out = new ArrayList<>();
        for (String r : v.reachProblems()) out.add(new ValidityProblem(0, null, r, true));
        for (String w : v.keyWarnings()) out.add(new ValidityProblem(0, null, w, false));
        var error = io.github.llm4j.loom.execution.ScriptValidator.Severity.ERROR;
        for (var p : v.problems())
            out.add(
                    new ValidityProblem(
                            p.line(), p.construct(), p.message(), p.severity() == error));
        return out;
    }

    private static GraphResult graph(ReviewCommand c) {
        try {
            return new GraphService().graph(c.script.toPath());
        } catch (RuntimeException e) {
            return null; // the graph only adds node ids; the review does not depend on it
        }
    }

    private static ReviewReport atLeast(ReviewReport r, Severity min) {
        return new ReviewReport(
                r.file(),
                r.findings().stream().filter(f -> f.severity().atLeast(min)).toList(),
                r.suppressed(),
                r.notes(),
                r.lensesRun(),
                r.lensesOff());
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

    private static int explain(String id, RuleCatalog catalog, WeaveEnv env) {
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
