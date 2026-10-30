package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.loop.GateConfig;
import io.github.llm4j.loom.loop.GateReport;
import io.github.llm4j.loom.loop.GateReport.Stage;
import io.github.llm4j.loom.loop.GateReport.Status;
import io.github.llm4j.loom.loop.Workflows;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave gate}: the checks a team has chosen, in order, with one verdict, for CI. It is only
 * ever run on purpose (nothing runs or deploys it). It reads the repository and the run store and
 * writes nothing to either; a stage that needs data that is not there is skipped, visibly, and
 * {@code --require} turns a skip into a failure.
 *
 * <p>Exit codes: 0 passed, 1 failed, 2 could not run (a bad option or configuration).
 */
@Command(
        name = "gate",
        description =
                "Runs the checks your team chose (check, review, test, regress, eval) and prints one verdict, for CI. Skipped stages are shown, and --require makes a skip a failure.")
final class GateCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "<script.loom>",
            description = "The workflow (default: main.loom here).")
    File script;

    @Option(
            names = "--config",
            description = "The gate configuration (default: gate.properties beside the script).")
    File config;

    @Option(
            names = "--base-ref",
            description = "Compare with this git revision (the base of a pull request).")
    String baseRef;

    @Option(
            names = "--candidate-ref",
            description = "Check the script at this git revision instead of the working copy.")
    String candidateRef;

    @Option(
            names = "--store",
            description = "The run store for the regress stage (overrides regress.store).")
    File store;

    @Option(
            names = "--require",
            split = ",",
            description =
                    "Stages that must run: a skip of one fails the gate (check, review, test, regress, eval).")
    List<String> require = new ArrayList<>();

    @Option(
            names = "--protocol",
            paramLabel = "<name>",
            description =
                    "Run the eval stage as a named protocol of eval-protocol.yaml (for example release), so a gate and a nightly run mean the same thing.")
    String protocol;

    @Option(
            names = "--include-observed",
            description = "Let captured cases that are still 'observed' fail the gate too.")
    boolean includeObserved;

    @Option(
            names = "--format",
            description = "text (default), md, junit or sarif (the review's findings).",
            defaultValue = "text")
    String format;

    @Option(
            names = "--print-workflow",
            description = "Print a starting CI configuration: github or jenkins.")
    String printWorkflow;

    @Override
    public Integer call() {
        return gate(this, WeaveEnv.system());
    }

    static int gate(GateCommand c, WeaveEnv env) {
        try {
            if (c.printWorkflow != null) {
                switch (c.printWorkflow) {
                    case "github" -> env.out().print(Workflows.github());
                    case "jenkins" -> env.out().print(Workflows.jenkins());
                    default -> {
                        env.err().println("Error: --print-workflow takes github or jenkins.");
                        return 1;
                    }
                }
                return 0;
            }
            if (!Set.of("text", "md", "junit", "sarif").contains(c.format)) {
                env.err().println("Error: --format takes text, md, junit or sarif.");
                return 1;
            }
            Set<String> stages = Set.of("check", "review", "test", "regress", "eval");
            for (String r : c.require)
                if (!stages.contains(r)) {
                    env.err()
                            .println(
                                    "Error: --require names "
                                            + r
                                            + "; the stages are "
                                            + new java.util.TreeSet<>(stages)
                                            + ".");
                    return 1;
                }
            Path working =
                    (c.script != null ? c.script.toPath() : Path.of("main.loom"))
                            .toAbsolutePath()
                            .normalize();
            if (!Files.isRegularFile(working)) {
                env.err().println("Error: no script at " + working + ".");
                return 2;
            }
            Path configFile =
                    c.config != null
                            ? c.config.toPath()
                            : working.getParent().resolve(GateConfig.FILE);
            GateConfig cfg = GateConfig.load(configFile);
            Path temp = Files.createTempDirectory("weave-gate-");
            try {
                Path script = working;
                if (c.candidateRef != null)
                    script =
                            io.github.llm4j.loom.loop.Baselines.checkoutAt(
                                    working, c.candidateRef, temp.resolve("candidate"));
                List<String> changed =
                        c.baseRef == null
                                ? List.of()
                                : changedFiles(working, c.baseRef, c.candidateRef);
                List<Stage> results = new ArrayList<>();
                results.add(check(script, cfg, env));
                results.add(review(script, cfg, env));
                results.add(test(script, cfg, c, env));
                results.add(regress(script, working, cfg, c, env, temp));
                results.add(eval(script, cfg, c.protocol, env));
                GateReport report =
                        new GateReport(
                                working.getFileName().toString(), results, changed, c.require);
                switch (c.format) {
                    case "md" -> env.out().print(report.markdown());
                    case "junit" -> env.out().print(report.junit());
                    case "sarif" -> env.out().print(reviewSarif(script, env));
                    default -> env.out().print(report.text());
                }
                return report.passed() ? 0 : 1;
            } finally {
                deleteTree(temp);
            }
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            env.err().println("Error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return 2;
        }
    }

    // ---- stages

    private static Stage check(Path script, GateConfig cfg, WeaveEnv env) {
        String reproduce = "weave check " + script.getFileName() + " --no-env --strict";
        if (!cfg.check())
            return new Stage("check", Status.SKIPPED, "off in gate.properties", reproduce);
        Buffered b = new Buffered(env);
        int code =
                WeaveCLI.check(
                        script.toFile(),
                        null,
                        new WeaveCLI.CheckSettings(false, false, true, true),
                        b.env);
        return new Stage(
                "check",
                code == 0 ? Status.PASS : Status.FAIL,
                code == 0 ? "the script checks clean" : b.firstLines(2),
                reproduce);
    }

    private static Stage review(Path script, GateConfig cfg, WeaveEnv env) {
        String reproduce =
                "weave review "
                        + script.getFileName()
                        + " --no-env --fail-on "
                        + cfg.reviewFailOn();
        if (cfg.reviewFailOn().equals("off"))
            return new Stage("review", Status.SKIPPED, "off in gate.properties", reproduce);
        Buffered b = new Buffered(env);
        ReviewCommand rc = new ReviewCommand();
        new CommandLine(rc)
                .parseArgs(script.toString(), "--no-env", "--fail-on", cfg.reviewFailOn());
        int code = ReviewCommand.review(rc, b.env);
        String summary = b.firstLines(1);
        return new Stage(
                "review",
                code == 0 ? Status.PASS : Status.FAIL,
                summary.isBlank()
                        ? (code == 0
                                ? "nothing at or above " + cfg.reviewFailOn()
                                : "findings at or above " + cfg.reviewFailOn())
                        : summary,
                reproduce);
    }

    private static Stage test(Path script, GateConfig cfg, GateCommand c, WeaveEnv env) {
        List<String> args = new ArrayList<>(List.of(script.toString()));
        if (!c.includeObserved) args.add("--ignore-observed");
        if (cfg.minCoverage() != null)
            args.addAll(List.of("--min-coverage", String.valueOf(cfg.minCoverage())));
        if (cfg.requireApproved()) args.add("--require-approved");
        String reproduce =
                "weave test "
                        + script.getFileName()
                        + args.stream().skip(1).map(a -> " " + a).reduce("", String::concat);
        if (!cfg.test())
            return new Stage("test", Status.SKIPPED, "off in gate.properties", reproduce);
        Buffered b = new Buffered(env);
        TestCommand tc = new TestCommand();
        new CommandLine(tc).parseArgs(args.toArray(new String[0]));
        int code = TestCommand.run(tc, b.env);
        if (code == 2 && b.errText().contains("no test cases found"))
            return new Stage(
                    "test", Status.SKIPPED, "the project has no tests/*.test.yaml", reproduce);
        String summary = b.lastLine("case");
        return new Stage(
                "test",
                code == 0 ? Status.PASS : Status.FAIL,
                summary.isBlank() ? (code == 0 ? "all cases pass" : b.errText().strip()) : summary,
                reproduce);
    }

    private static Stage regress(
            Path script, Path working, GateConfig cfg, GateCommand c, WeaveEnv env, Path temp)
            throws Exception {
        Path storePath =
                c.store != null
                        ? c.store.toPath()
                        : cfg.store() == null ? null : Path.of(cfg.store());
        String reproduce =
                "weave regress "
                        + working.getFileName()
                        + " --store "
                        + (storePath == null ? "<run store>" : storePath)
                        + (c.baseRef != null ? " --baseline-git " + c.baseRef : "")
                        + " --fail-on "
                        + cfg.regressFailOn();
        if (!cfg.regress())
            return new Stage("regress", Status.SKIPPED, "off in gate.properties", reproduce);
        if (storePath == null || "off".equals(cfg.store()))
            return new Stage(
                    "regress",
                    Status.SKIPPED,
                    "no run store is configured (regress.store), so nothing was compared with real history",
                    reproduce);
        if (!Files.isDirectory(storePath))
            return new Stage(
                    "regress",
                    Status.SKIPPED,
                    "the run store "
                            + storePath
                            + " is not here (CI has no production data), so nothing was compared with real history",
                    reproduce);
        Buffered b = new Buffered(env);
        List<String> args =
                new ArrayList<>(
                        List.of(
                                script.toString(),
                                "--store",
                                storePath.toString(),
                                "--fail-on",
                                cfg.regressFailOn(),
                                "--limit",
                                String.valueOf(cfg.limit())));
        if (c.baseRef != null) {
            // the base is read from the repository the script lives in, not from the extracted copy
            Path base =
                    io.github.llm4j.loom.loop.Baselines.checkoutAt(
                            working, c.baseRef, temp.resolve("base"));
            args.addAll(List.of("--baseline", base.toString()));
        }
        if (cfg.since() != null) args.addAll(List.of("--since", cfg.since()));
        RegressCommand rc = new RegressCommand();
        new CommandLine(rc).parseArgs(args.toArray(new String[0]));
        int code = RegressCommand.regress(rc, b.env);
        if (code == 2)
            return new Stage(
                    "regress",
                    Status.SKIPPED,
                    "no run could be compared: " + b.errText().strip().replaceFirst("^Error: ", ""),
                    reproduce);
        String summary = b.lastLine("UNSAFE FLIPS");
        return new Stage(
                "regress",
                code == 0 ? Status.PASS : Status.FAIL,
                summary.isBlank()
                        ? (code == 0
                                ? "no unsafe change in the sample"
                                : "a change at or above " + cfg.regressFailOn())
                        : summary.strip(),
                reproduce);
    }

    private static Stage eval(Path script, GateConfig cfg, String protocol, WeaveEnv env) {
        String reproduce = "weave eval " + script.getFileName() + (protocol == null ? "" : " --protocol " + protocol);
        if (!cfg.eval())
            return new Stage(
                    "eval",
                    Status.SKIPPED,
                    "off (it spends money on real models; run it nightly)",
                    reproduce);
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
            int code =
                    protocol == null
                            ? new CommandLine(new EvalCommand()).execute(script.toString())
                            : new CommandLine(new EvalCommand()).execute(script.toString(), "--protocol", protocol);
            String last =
                    buf.toString(StandardCharsets.UTF_8)
                            .lines()
                            .filter(l -> !l.isBlank())
                            .reduce((a, b) -> b)
                            .orElse("");
            return new Stage("eval", code == 0 ? Status.PASS : Status.FAIL, last, reproduce);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private static String reviewSarif(Path script, WeaveEnv env) {
        Buffered b = new Buffered(env);
        ReviewCommand rc = new ReviewCommand();
        new CommandLine(rc).parseArgs(script.toString(), "--no-env", "--format", "sarif");
        ReviewCommand.review(rc, b.env);
        return b.outText();
    }

    // ---- git

    private static List<String> changedFiles(Path script, String base, String candidate)
            throws Exception {
        Path dir = script.getParent();
        List<String> cmd = new ArrayList<>(List.of("git", "diff", "--name-only", base));
        if (candidate != null) cmd.add(candidate);
        cmd.addAll(List.of("--", "."));
        Process p =
                new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(false).start();
        List<String> out = new ArrayList<>();
        new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .lines()
                .filter(l -> !l.isBlank())
                .forEach(out::add);
        p.getErrorStream().readAllBytes();
        return p.waitFor() == 0 ? out : List.of();
    }

    /** An env whose output is kept, so a stage can be summarised. */
    private static final class Buffered {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final WeaveEnv env;

        Buffered(WeaveEnv e) {
            env =
                    new WeaveEnv(
                            e.models(),
                            e.human(),
                            new PrintStream(out, true, StandardCharsets.UTF_8),
                            new PrintStream(err, true, StandardCharsets.UTF_8),
                            e.clock(),
                            e.sleeper(),
                            e.commands(),
                            e.weave(),
                            e.env(),
                            e.askVia(),
                            e.secrets(),
                            e.prompts(),
                            e.observe(),
                            e.invoker());
        }

        String outText() {
            return out.toString(StandardCharsets.UTF_8);
        }

        String errText() {
            return err.toString(StandardCharsets.UTF_8);
        }

        String firstLines(int n) {
            return (outText() + errText())
                    .lines()
                    .filter(l -> !l.isBlank())
                    .limit(n)
                    .reduce((a, b) -> a + " / " + b)
                    .orElse("");
        }

        String lastLine(String containing) {
            return outText()
                    .lines()
                    .filter(l -> l.contains(containing))
                    .reduce((a, b) -> b)
                    .orElse("")
                    .strip();
        }
    }

    private static void deleteTree(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
            // a temporary folder that will not go is not worth failing over
        }
    }
}
