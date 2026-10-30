package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.graph.GraphNode;
import io.github.llm4j.loom.graph.WorkflowGraph;
import io.github.llm4j.loom.wtest.Coverage;
import io.github.llm4j.loom.wtest.Reports;
import io.github.llm4j.loom.wtest.TestFile;
import io.github.llm4j.loom.wtest.TestRunner;
import io.github.llm4j.loom.wtest.Watch;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave test}: runs the cases of {@code *.test.yaml} files against a script with scripted
 * models, so the logic of a workflow (which branch, which step, what happens on failure) is tested
 * with no model, no key, no network and no spend. Exit 0 all passed, 1 a case failed (or coverage
 * is below {@code --min-coverage}), 2 it could not run (a test file or the script is unsound).
 */
@Command(
        name = "test",
        description =
                "Runs test cases against a workflow with scripted models and no keys: routes, calls and results are checked, nothing is spent.")
final class TestCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "The .loom script.")
    File script;

    @Option(
            names = "--tests",
            paramLabel = "<file|dir>",
            description =
                    "Test files (default: tests/ beside the script, *.test.yaml). Repeatable.")
    List<File> tests = new ArrayList<>();

    @Option(
            names = "--case",
            paramLabel = "<name|glob>",
            description = "Run the cases whose name matches.")
    String caseName;

    @Option(
            names = "--tag",
            paramLabel = "<tag>",
            description = "Run only cases with this tag. Repeatable.")
    List<String> tags = new ArrayList<>();

    @Option(
            names = "--exclude-tag",
            paramLabel = "<tag>",
            description = "Skip cases with this tag. Repeatable.")
    List<String> excludeTags = new ArrayList<>();

    @Option(names = "--check", description = "Validate the test files only; run nothing.")
    boolean check;

    @Option(
            names = "--ignore-observed",
            description =
                    "Report, but do not fail on, captured cases that are still 'observed' (only approved cases gate; weave gate uses this).")
    boolean ignoreObserved;

    @Option(
            names = "--require-approved",
            description =
                    "Fail when a captured case is still 'observed' (for CI: nothing unreviewed is merged).")
    boolean requireApproved;

    @Option(names = "--steps", description = "List the steps a test can name, then exit.")
    boolean steps;

    @Option(names = "--show-case", paramLabel = "<name>", description = "Print the merged case.")
    String showCase;

    @Option(
            names = "--coverage",
            description = "Print the nodes no case reached, with a hint for a case that would.")
    boolean coverage;

    @Option(
            names = "--min-coverage",
            paramLabel = "<percent>",
            description = "Exit 1 when node coverage is below this percent.")
    Integer minCoverage;

    @Option(
            names = "--lenient",
            description = "Let an unscripted model call answer neutrally (it is reported).")
    boolean lenient;

    @Option(
            names = "--shuffle",
            description = "Run the cases in a random order, to show they do not depend on it.")
    boolean shuffle;

    @Option(
            names = "--seed",
            paramLabel = "<n>",
            description = "The seed of --shuffle, to repeat an order.")
    Long seed;

    @Option(
            names = "--parallel",
            paramLabel = "<n>",
            description = "Run this many cases at once (default: the number of processors).")
    Integer parallel;

    @Option(names = "--fail-fast", description = "Stop at the first failing case.")
    boolean failFast;

    @Option(
            names = "--format",
            description = "text (default), json, junit or tap.",
            defaultValue = "text")
    String format;

    @Option(
            names = "--watch",
            description = "Run again whenever the script, its prompts or the tests change.")
    boolean watch;

    @Option(
            names = {"-l", "--loot"},
            description =
                    "The .loot tool mapping file, for a script that uses your own tools. A test scripts every tool, so it is only checked to exist here; it is accepted so the same command line works for run, eval and test.")
    File lootFile;

    @Mixin ClassesOptions classesOptions = new ClassesOptions();

    @Mixin EnvFileOptions envFile = new EnvFileOptions();

    /** The environment with the keys of the project's .env added; null (after saying why) when the file is refused. */
    private static WeaveEnv withKeys(TestCommand c, WeaveEnv base) {
        return c.script == null ? base : c.envFile.apply(base, c.script.toPath());
    }

    @Override
    public Integer call() {
        return run(this, WeaveEnv.system());
    }

    static int run(TestCommand c, WeaveEnv base) {
        final WeaveEnv env = withKeys(c, base);
        if (env == null) return 2;
        if (!List.of("text", "json", "junit", "tap").contains(c.format)) {
            env.err().println("Error: --format takes text, json, junit or tap.");
            return 2;
        }
        if (c.lootFile != null && !c.lootFile.exists()) {
            env.err().println("Error: --loot " + c.lootFile + " does not exist.");
            return 2;
        }
        if (c.script != null && !c.classesOptions.apply(c.script.toPath(), env)) return 2;
        List<Path> paths = new ArrayList<>();
        for (File f : c.tests) paths.add(f.toPath());
        if (paths.isEmpty()) {
            if (c.script == null) {
                env.err().println("Error: weave test needs a script, or --tests <file|dir>.");
                return 2;
            }
            File beside = c.script.getAbsoluteFile().getParentFile();
            paths.add(new File(beside, "tests").toPath());
        }
        TestRunner.Suite suite;
        try {
            suite = TestRunner.load(c.script == null ? null : c.script.toPath(), paths);
        } catch (Exception e) {
            env.err().println("Error: " + firstLine(e));
            return 2;
        }
        if (c.steps) return steps(suite, env);
        if (!suite.problems().isEmpty()) {
            suite.problems().forEach(p -> env.err().println("Error: " + p));
            env.err()
                    .println(
                            suite.problems().size()
                                    + " problem(s) in the test files; nothing was run.");
            return 2;
        }
        if (suite.files().stream().allMatch(f -> f.cases().isEmpty())) {
            env.err().println("Error: no test cases found in " + paths + ".");
            return 2;
        }
        if (c.showCase != null) return show(suite, c.showCase, env);
        if (c.check) {
            int n = suite.files().stream().mapToInt(f -> f.cases().size()).sum();
            env.out().println(n + " case(s) in " + suite.files().size() + " file(s) are sound.");
            return 0;
        }
        int code = runOnce(c, suite, env, null);
        if (!c.watch) return code;
        return watch(c, suite, paths, env, () -> !Thread.currentThread().isInterrupted(), 500);
    }

    /** Runs the selected cases of a suite and prints the report; returns the exit code. */
    private static int runOnce(
            TestCommand c, TestRunner.Suite suite, WeaveEnv env, Set<Path> onlyFiles) {
        TestRunner.Suite use = suite;
        if (onlyFiles != null && !onlyFiles.isEmpty()) {
            List<TestFile> chosen = new ArrayList<>();
            for (TestFile f : suite.files())
                if (onlyFiles.contains(f.path().toAbsolutePath().normalize())) chosen.add(f);
            use =
                    new TestRunner.Suite(
                            suite.script(),
                            suite.loaded(),
                            suite.graph(),
                            chosen,
                            suite.problems());
        }
        int cpus = Runtime.getRuntime().availableProcessors();
        TestRunner.Options options =
                new TestRunner.Options(
                        c.caseName,
                        c.lenient,
                        c.failFast,
                        c.tags,
                        c.excludeTags,
                        c.failFast
                                ? 1
                                : c.parallel == null
                                        ? cpus
                                        : c.parallel, // stopping early needs an order
                        c.shuffle,
                        c.shuffle
                                ? Long.valueOf(
                                        TestRunner.seedOf(
                                                new TestRunner.Options(
                                                        null, false, false, null, null, 1, true,
                                                        c.seed)))
                                : null);
        long start = System.nanoTime();
        List<TestRunner.CaseResult> results = TestRunner.run(use, options);
        long ms = (System.nanoTime() - start) / 1_000_000;
        if (results.isEmpty()) {
            boolean onlyName = c.caseName != null && c.tags.isEmpty() && c.excludeTags.isEmpty();
            env.err()
                    .println(
                            "Error: no case matches "
                                    + (onlyName ? c.caseName : "the selection")
                                    + ".");
            return 2;
        }
        Coverage cov = TestRunner.coverage(use, results);
        Reports.Run run =
                new Reports.Run(
                        suite.script().getFileName().toString(),
                        results,
                        cov,
                        ms,
                        options.shuffle() ? options.seed() : null);
        switch (c.format) {
            case "json" -> env.out().print(Reports.json(run));
            case "junit" -> env.out().print(Reports.junit(run));
            case "tap" -> env.out().print(Reports.tap(run));
            default -> {
                env.out().print(Reports.text(run));
                if (c.coverage) env.out().print(Reports.uncovered(cov));
            }
        }
        java.util.Map<String, Integer> trust =
                io.github.llm4j.loom.loop.Approval.counts(use.files());
        int waiting = trust.get("observed");
        if (waiting > 0 && c.format.equals("text"))
            env.out()
                    .println(
                            waiting
                                    + " captured case"
                                    + (waiting == 1 ? " waits" : "s wait")
                                    + " for a person to approve "
                                    + (waiting == 1 ? "it" : "them")
                                    + " (weave capture --approve <file>); only approved cases fail a gate.");
        long gating = results.stream().filter(x -> !x.passed()).count();
        if (c.ignoreObserved) {
            java.util.Set<String> observedFiles = new java.util.HashSet<>();
            for (TestFile f : use.files())
                if ("observed".equals(f.capturedStatus())) observedFiles.add(f.path().toString());
            long observedFailing =
                    results.stream()
                            .filter(x -> !x.passed() && observedFiles.contains(x.file()))
                            .count();
            if (observedFailing > 0) {
                gating -= observedFailing;
                env.out()
                        .println(
                                observedFailing
                                        + " captured case"
                                        + (observedFailing == 1 ? "" : "s")
                                        + " that "
                                        + (observedFailing == 1 ? "is" : "are")
                                        + " still observed "
                                        + (observedFailing == 1 ? "fails" : "fail")
                                        + " and "
                                        + (observedFailing == 1 ? "does" : "do")
                                        + " not gate; approve or retire "
                                        + (observedFailing == 1 ? "it" : "them")
                                        + ".");
            }
        }
        if (gating > 0) return 1;
        if (c.requireApproved && waiting > 0) {
            env.err()
                    .println(
                            "Error: "
                                    + waiting
                                    + " captured case"
                                    + (waiting == 1 ? " is" : "s are")
                                    + " still observed; approve or retire "
                                    + (waiting == 1 ? "it" : "them")
                                    + " (--require-approved).");
            return 1;
        }
        if (c.minCoverage != null && cov.percent() < c.minCoverage) {
            env.err()
                    .println(
                            "Node coverage is "
                                    + cov.percent()
                                    + " %, below --min-coverage "
                                    + c.minCoverage
                                    + ".");
            return 1;
        }
        return 0;
    }

    /**
     * Runs again whenever something the run depends on changes: only the cases of a changed test
     * file when that is all that changed, else every case. Looks every {@code pollMillis} while
     * {@code more} says to go on.
     */
    static int watch(
            TestCommand c,
            TestRunner.Suite first,
            List<Path> paths,
            WeaveEnv env,
            BooleanSupplier more,
            long pollMillis) {
        Path script = first.script();
        TestRunner.Suite suite = first;
        List<Path> files = Watch.watched(script, paths, suite);
        Map<Path, Long> seen = Watch.snapshot(files);
        env.out().println("watching " + files.size() + " file(s); change one to run again");
        int last = 0;
        while (more.getAsBoolean()) {
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
            Map<Path, Long> now = Watch.snapshot(Watch.watched(script, paths, suite));
            Set<Path> changed = Watch.changed(seen, now);
            if (changed.isEmpty()) continue;
            seen = now;
            try {
                suite = TestRunner.load(script, paths);
            } catch (Exception e) {
                env.err().println("Error: " + firstLine(e));
                continue;
            }
            if (!suite.problems().isEmpty()) {
                suite.problems().forEach(p -> env.err().println("Error: " + p));
                continue;
            }
            Set<Path> only = Watch.onlyTestFiles(changed, suite.files());
            env.out()
                    .println(
                            "changed: "
                                    + changed.stream().map(p -> p.getFileName().toString()).toList()
                                    + (only.isEmpty()
                                            ? " - running every case"
                                            : " - running the cases of those test files"));
            last = runOnce(c, suite, env, only);
            seen = Watch.snapshot(Watch.watched(script, paths, suite));
        }
        return last;
    }

    private static int steps(TestRunner.Suite suite, WeaveEnv env) {
        for (WorkflowGraph w : suite.graph().workflows()) {
            env.out().println(w.name() + ":");
            for (GraphNode n : w.nodes()) {
                if (n.kind().equals("start") || n.kind().equals("end")) continue;
                env.out()
                        .println(
                                String.format(
                                        "  %-5s line %-4s %s",
                                        n.id(),
                                        n.source() == null
                                                ? "-"
                                                : String.valueOf(n.source().line()),
                                        n.label()));
            }
        }
        return 0;
    }

    private static int show(TestRunner.Suite suite, String name, WeaveEnv env) {
        boolean any = false;
        for (TestFile f : suite.files())
            for (TestFile.Case k : f.cases())
                if (k.name().equals(name)) {
                    env.out().print(k.yaml());
                    any = true;
                }
        if (!any) env.err().println("Error: no case named \"" + name + "\".");
        return any ? 0 : 2;
    }

    private static String firstLine(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.lines().findFirst().orElse("");
    }
}
