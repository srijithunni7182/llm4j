package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.graph.GraphNode;
import io.github.llm4j.loom.graph.WorkflowGraph;
import io.github.llm4j.loom.wtest.TestFile;
import io.github.llm4j.loom.wtest.TestRunner;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave test}: runs the cases of {@code *.test.yaml} files against a script with scripted
 * models, so the logic of a workflow (which branch, which step, what happens on failure) is tested
 * with no model, no key, no network and no spend. Exit 0 all passed, 1 a case failed, 2 it could
 * not run (a test file or the script is unsound).
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

    @Option(names = "--check", description = "Validate the test files only; run nothing.")
    boolean check;

    @Option(names = "--steps", description = "List the steps a test can name, then exit.")
    boolean steps;

    @Option(names = "--show-case", paramLabel = "<name>", description = "Print the merged case.")
    String showCase;

    @Option(
            names = "--lenient",
            description = "Let an unscripted model call answer neutrally (it is reported).")
    boolean lenient;

    @Option(names = "--fail-fast", description = "Stop at the first failing case.")
    boolean failFast;

    @Override
    public Integer call() {
        return run(this, WeaveEnv.system());
    }

    static int run(TestCommand c, WeaveEnv env) {
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
        long start = System.nanoTime();
        List<TestRunner.CaseResult> results =
                TestRunner.run(suite, c.caseName, c.lenient, c.failFast);
        if (results.isEmpty()) {
            env.err().println("Error: no case matches " + c.caseName + ".");
            return 2;
        }
        int failed = 0;
        for (TestRunner.CaseResult r : results) {
            env.out()
                    .println(
                            String.format(
                                    "%-5s %s  (%d ms)",
                                    r.passed() ? "ok" : "FAIL", r.name(), r.millis()));
            for (String f : r.failures())
                env.out().println("        - " + f.replace("\n", "\n          "));
            for (String n : r.notes()) env.out().println("        ~ lenient: " + n);
            if (!r.passed()) failed++;
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        env.out()
                .println(
                        results.size()
                                - failed
                                + " passed, "
                                + failed
                                + " failed in "
                                + (ms < 1000 ? ms + " ms" : String.format("%.1f s", ms / 1000.0)));
        return failed == 0 ? 0 : 1;
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
