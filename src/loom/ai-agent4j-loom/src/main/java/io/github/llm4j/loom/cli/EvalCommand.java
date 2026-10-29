package io.github.llm4j.loom.cli;

import io.github.llm4j.LLMClient;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.eval.DatasetFolder;
import io.github.llm4j.loom.eval.EvalReport;
import io.github.llm4j.loom.eval.EvalSections;
import io.github.llm4j.loom.eval.Splits;
import io.github.llm4j.loom.eval.EvalRunner;
import io.github.llm4j.loom.eval.Fixtures;
import io.github.llm4j.loom.eval.MockModels;
import io.github.llm4j.loom.eval.RubricJudge;
import io.github.llm4j.loom.eval.ScenarioResult;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.prompt.PromptSupport;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave eval}: runs the golden dataset of a script against it and says what passed, what
 * failed and what nothing judged. Needs no Java: the dataset is YAML files in {@code eval/golden}.
 * {@code --mock} runs on a model that costs nothing, to check the wiring; a real run needs a cap.
 */
@Command(
        name = "eval",
        description =
                "Runs a script's golden dataset (eval/golden/*.yaml) against it and reports passed, failed and unjudged. --mock costs nothing; --check validates the dataset without calling a model; --init creates one.")
final class EvalCommand implements Callable<Integer> {

    /** What a real run is limited to when no limit is given: a message says so. */
    static final long DEFAULT_MAX_TOKENS = 500_000;

    @Parameters(index = "0", description = "The .loom script.")
    File script;

    @Option(
            names = {"-l", "--loot"},
            description =
                    "The .loot tool mapping file, for a script that uses your own tools (a real run calls them; --mock does not).")
    File lootFile;

    @Option(
            names = "--verbose",
            description =
                    "Show what each agent is doing as it runs (by default only the result is shown).")
    boolean verbose;

    @Option(
            names = "--dataset",
            paramLabel = "<dir>",
            description = "The folder of dataset files (default: eval/golden beside the script).")
    File dataset;

    @Option(names = "--agent", description = "Only this agent's scenarios.")
    String agent;

    @Option(
            names = "--workflow",
            description =
                    "Only this workflow's scenarios (and which workflow workflow.yaml is for).")
    String workflow;

    @Option(
            names = "--mock",
            description =
                    "Run on a model that costs nothing and answers every step with a fixed, well-formed reply: it checks the wiring, not the quality.")
    boolean mock;

    @Option(
            names = "--check",
            description =
                    "Only validate the dataset: names, fields, dimensions, fixtures. No model is called.")
    boolean check;

    @Option(
            names = "--init",
            description =
                    "Create eval/golden with one starter scenario per agent and one for the workflow. Never overwrites a file.")
    boolean init;

    @Option(
            names = "--judge",
            paramLabel = "<model>",
            description =
                    "The model that judges rubric and expect lines (default: the agent's own model).")
    String judge;

    @Option(
            names = "--max-tokens",
            description = "Cap the tokens of a real run (default " + DEFAULT_MAX_TOKENS + ").")
    Long maxTokens;

    @Option(
            names = "--baseline",
            description =
                    "Compare this run with the saved baselines (the current one and the one before it) and list the cases that regressed. Refused when the dataset differs, unless --allow-version-change is given.")
    boolean baseline;

    @Option(
            names = "--save-baseline",
            description =
                    "After a finished real run, save its results as a new baseline. Needs --approver and --why; a baseline is never replaced.")
    boolean saveBaseline;

    @Option(names = "--approver", paramLabel = "<name>", description = "With --save-baseline: who approves this baseline.")
    String approver;

    @Option(names = "--why", paramLabel = "<text>", description = "With --save-baseline: why this run is the new baseline.")
    String why;

    @Option(
            names = "--allow-version-change",
            paramLabel = "<why>",
            description =
                    "With --baseline: compare with a baseline taken on a different dataset anyway; the reason is recorded in the report.")
    String allowVersionChange;

    @Option(
            names = "--split",
            paramLabel = "development|validation|heldout",
            description =
                    "Run only the cases tagged split:<name> (a case with no split tag is development). heldout needs --release-gate.")
    String split;

    @Option(
            names = "--release-gate",
            description =
                    "This run is the release gate: held-out cases (split:heldout) run too, and the report records that it was a gate run. Without it held-out cases never run.")
    boolean releaseGate;

    @Option(
            names = "--repetitions",
            paramLabel = "<n>",
            description =
                    "Run each case this many times (default 1, at most 10): the report then gives pass@1, pass^N, consistency and the unstable cases. Every repetition is a paid run.")
    int repetitions = 1;

    @Option(
            names = "--judge-samples",
            paramLabel = "<n>",
            description =
                    "Judge each rubric line this many times and use the median (default 1, at most 9). Samples that disagree widely leave the line unjudged. Each sample is a paid call.")
    int judgeSamples = 1;

    @Option(names = "--max-calls", description = "Cap the model calls of a real run.")
    Long maxCalls;

    @Option(
            names = "--max-cost",
            description = "Cap the cost of a real run, e.g. 0.50 (needs --prices).")
    String maxCost;

    @Option(
            names = "--prices",
            description = "Price table: lines of 'model = input / output' per million tokens.")
    File prices;

    @Option(
            names = {"-y", "--yes"},
            description = "Do not ask before a real run.")
    boolean yes;

    @Option(
            names = "--json",
            paramLabel = "<file>",
            description = "Also write the results as JSON.")
    File json;

    @Option(
            names = "--report",
            paramLabel = "<file>",
            description = "Also write the results as a single HTML page.")
    File report;

    @Mixin PromptOptions promptOptions = new PromptOptions();

    @Mixin SecretOptions secrets = new SecretOptions();

    @Mixin EnvFileOptions envFile = new EnvFileOptions();

    @Mixin ClassesOptions classesOptions = new ClassesOptions();

    @Mixin ContextOptions contextOptions = new ContextOptions();

    @Override
    public Integer call() {
        WeaveEnv env = envFile.apply(WeaveEnv.system(), script.toPath());
        if (env != null
                && (!classesOptions.apply(script.toPath(), env)
                        || !contextOptions.apply(script.toPath(), env))) return 2;
        env = env == null ? null : secrets.apply(env, Prompts.console());
        env = env == null ? null : promptOptions.apply(env);
        return env == null ? 2 : eval(this, env);
    }

    static int eval(EvalCommand c, WeaveEnv env) {
        java.util.logging.Logger loom = java.util.logging.Logger.getLogger("io.github.llm4j.loom");
        java.util.logging.Level before = loom.getLevel();
        loom.setLevel(
                c.verbose
                        ? java.util.logging.Level.INFO
                        : java.util.logging.Level
                                .WARNING); // the progress lines would bury the report
        try {
            return evaluate(c, env);
        } finally {
            loom.setLevel(before);
        }
    }

    private static int evaluate(EvalCommand c, WeaveEnv env) {
        if (c.check && c.init) {
            env.err().println("Error: --check and --init cannot be used together.");
            return 2;
        }
        if (c.split != null && !Splits.ALL.contains(c.split)) {
            env.err()
                    .println(
                            "Error: --split takes development, validation or heldout, got " + c.split + ".");
            return 2;
        }
        if (Splits.HELD_OUT.equals(c.split) && !c.releaseGate) {
            env.err()
                    .println(
                            "Error: held-out cases run only at a release gate, so that no one looks at them by accident while tuning. Add --release-gate (the report will record that this was a gate run).");
            return 2;
        }
        if (c.saveBaseline) {
            String missing =
                    c.approver == null || c.approver.isBlank()
                            ? "--approver <name>"
                            : c.why == null || c.why.isBlank() ? "--why \"<reason>\"" : null;
            if (missing != null) {
                env.err().println("Error: --save-baseline needs " + missing + " (a baseline is a decision someone owns and explains).");
                return 2;
            }
            if (c.mock) {
                env.err().println("Error: a mock run judged nothing, so it cannot be a baseline.");
                return 2;
            }
        }
        if (c.repetitions < 1 || c.repetitions > MAX_REPETITIONS) {
            env.err()
                    .println(
                            "Error: --repetitions must be between 1 and "
                                    + MAX_REPETITIONS
                                    + ", got "
                                    + c.repetitions
                                    + ".");
            return 2;
        }
        if (c.judgeSamples < 1 || c.judgeSamples > RubricJudge.MAX_SAMPLES) {
            env.err()
                    .println(
                            "Error: --judge-samples must be between 1 and "
                                    + RubricJudge.MAX_SAMPLES
                                    + ", got "
                                    + c.judgeSamples
                                    + ".");
            return 2;
        }
        Path scriptFile = c.script.toPath().toAbsolutePath();
        LoomScript script;
        try {
            script = new LoomLoader().load(scriptFile.toString());
        } catch (Exception e) {
            env.err().println("Error: " + c.script + " could not be read: " + e.getMessage());
            return 2;
        }
        Path dir = DatasetFolder.locate(scriptFile, c.dataset == null ? null : c.dataset.toPath());
        if (c.init) return init(script, dir, env);

        DatasetFolder.Plan plan = DatasetFolder.plan(script, dir, c.workflow);
        List<String> problems = new ArrayList<>(plan.problems());
        for (DatasetFolder.Target t : plan.targets()) {
            for (EvalScenario s : t.scenarios()) {
                String label =
                        t.file()
                                + (s.id() != null
                                        ? " " + s.id()
                                        : s.name() != null ? " " + s.name() : "");
                String named = Splits.named(s);
                if (named != null && !Splits.ALL.contains(named))
                    problems.add(
                            label
                                    + ": split:"
                                    + named
                                    + " is not a split; use development, validation or heldout");
                if (t.isAgent()) {
                    if (!s.namedInputs().isEmpty())
                        problems.add(
                                label
                                        + ": inputs: is for a workflow's parameters; an agent's task is input:");
                } else {
                    String bad = EvalRunner.inputProblem(t.name(), parameters(script, t.name()), s);
                    if (bad != null) problems.add(label + ": " + bad);
                }
            }
        }
        Fixtures fixtures = Fixtures.none();
        try {
            fixtures = Fixtures.read(dir);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }
        if (!DatasetFolder.exists(dir)) {
            env.err()
                    .println(
                            "Error: there is no dataset folder at "
                                    + dir
                                    + ". Create one with: weave eval "
                                    + c.script
                                    + " --init");
            return 2;
        }
        if (plan.targets().isEmpty() && problems.isEmpty())
            problems.add(
                    dir
                            + " has no dataset files (a file per agent or workflow, such as "
                            + firstName(script)
                            + ".yaml)");
        if (!problems.isEmpty()) {
            problems.forEach(p -> env.out().println("✗ " + p));
            env.out()
                    .println(
                            problems.size()
                                    + " problem"
                                    + (problems.size() == 1 ? "" : "s")
                                    + " in "
                                    + dir);
            return 2;
        }
        if (c.check) {
            long files = plan.targets().size();
            env.out()
                    .println(
                            "✓ "
                                    + dir.getFileName()
                                    + ": "
                                    + plan.scenarioCount()
                                    + " scenario"
                                    + (plan.scenarioCount() == 1 ? "" : "s")
                                    + " in "
                                    + files
                                    + " file"
                                    + (files == 1 ? "" : "s")
                                    + (fixtures.names().isEmpty()
                                            ? ""
                                            : ", fixtures for "
                                                    + String.join(", ", fixtures.names()))
                                    + ", ready to run");
            boolean anyHeldOut =
                    plan.targets().stream()
                            .flatMap(t -> t.scenarios().stream())
                            .anyMatch(s -> Splits.HELD_OUT.equals(Splits.of(s)));
            if (!anyHeldOut)
                env.out()
                        .println(
                                "note: no held-out cases. Tag a sealed set split:heldout, so that you can tell whether prompts were tuned to the cases you can see.");
            return 0;
        }
        return run(c, env, script, scriptFile, plan, fixtures);
    }

    // ── running ──────────────────────────────────────────────────────────────────────────────

    private static int run(
            EvalCommand c,
            WeaveEnv env,
            LoomScript script,
            Path scriptFile,
            DatasetFolder.Plan plan,
            Fixtures fixtures) {
        io.github.llm4j.loom.eval.DatasetIdentity identity = io.github.llm4j.loom.eval.DatasetIdentity.of(plan.dir());
        Path project = plan.dir().toAbsolutePath().getParent() == null ? null : plan.dir().toAbsolutePath().getParent().getParent();
        List<io.github.llm4j.loom.eval.Baselines.Baseline> saved =
                project == null ? List.of() : io.github.llm4j.loom.eval.Baselines.load(project);
        if (c.baseline && c.allowVersionChange == null) {
            var refusal = io.github.llm4j.loom.eval.Baselines.mismatch(saved, identity);
            if (refusal.isPresent()) {
                env.err().println("Error: " + refusal.get());
                return 2;
            }
        }
        List<DatasetFolder.Target> targets = new ArrayList<>();
        int heldOutLeftOut = 0;
        for (DatasetFolder.Target t : plan.targets()) {
            if (c.agent != null && !(t.isAgent() && t.name().equalsIgnoreCase(c.agent))) continue;
            if (c.workflow != null && !(!t.isAgent() && t.name().equalsIgnoreCase(c.workflow)))
                continue;
            List<EvalScenario> chosen = new ArrayList<>();
            for (EvalScenario s : t.scenarios()) {
                String split = Splits.of(s);
                if (c.split != null && !c.split.equals(split)) continue;
                if (split.equals(Splits.HELD_OUT) && !c.releaseGate) {
                    heldOutLeftOut++;
                    continue;
                }
                chosen.add(s);
            }
            if (!chosen.isEmpty())
                targets.add(new DatasetFolder.Target(t.kind(), t.name(), t.file(), chosen));
        }
        final int heldOutLeftOutCount = heldOutLeftOut;
        if (targets.isEmpty()) {
            env.err()
                    .println(
                            "Error: no scenarios match"
                                    + (c.agent != null ? " --agent " + c.agent : "")
                                    + (c.workflow != null ? " --workflow " + c.workflow : "")
                                    + ". The dataset covers: "
                                    + String.join(
                                            ", ",
                                            plan.targets().stream()
                                                    .map(DatasetFolder.Target::name)
                                                    .toList())
                                    + ".");
            return 2;
        }
        BigDecimal cost = null;
        if (c.maxCost != null) {
            if (c.prices == null) {
                env.err()
                        .println(
                                "Error: --max-cost needs --prices <file> (lines of 'model = input / output' per million tokens).");
                return 2;
            }
            try {
                cost = new BigDecimal(c.maxCost.strip().replaceFirst("^\\$", ""));
            } catch (NumberFormatException e) {
                env.err()
                        .println(
                                "Error: --max-cost takes a number such as 0.50, not "
                                        + c.maxCost
                                        + ".");
                return 2;
            }
            if (cost.signum() <= 0) {
                env.err().println("Error: budget limits must be positive.");
                return 2;
            }
        }
        if ((c.maxTokens != null && c.maxTokens <= 0) || (c.maxCalls != null && c.maxCalls <= 0)) {
            env.err().println("Error: budget limits must be positive.");
            return 2;
        }
        int scenarios = targets.stream().mapToInt(t -> t.scenarios().size()).sum();
        long judgedLines =
                targets.stream()
                        .flatMap(t -> t.scenarios().stream())
                        .mapToLong(s -> s.rubricLines().size() + s.expectLines().size())
                        .sum();
        Long tokenCap =
                c.mock
                        ? null
                        : (c.maxTokens != null || c.maxCalls != null || cost != null
                                ? c.maxTokens
                                : Long.valueOf(DEFAULT_MAX_TOKENS));
        if (!c.mock) {
            if (c.maxTokens == null && c.maxCalls == null && cost == null) {
                env.out()
                        .println(
                                "No limit was given, so a real run is capped at "
                                        + DEFAULT_MAX_TOKENS
                                        + " tokens (--max-tokens, --max-calls or --max-cost change that).");
            }
            env.out()
                    .println(
                            "This will make real model calls: "
                                    + scenarios
                                    + " scenario"
                                    + (scenarios == 1 ? "" : "s")
                                    + (c.repetitions > 1 ? ", each run " + c.repetitions + " times" : "")
                                    + ", at least one agent call each, and up to "
                                    + judgedLines * c.repetitions * c.judgeSamples
                                    + " judge call"
                                    + (judgedLines * c.repetitions * c.judgeSamples == 1 ? "" : "s")
                                    + ". Use --mock to check the wiring for free.");
            if (judgedLines > 0) env.out().println(judgeLine(script, targets, c.judge, env));
            if (!c.yes
                    && !env.human()
                            .promptHuman("Run it? (yes/no)")
                            .strip()
                            .toLowerCase()
                            .startsWith("y")) {
                env.out().println("Cancelled; nothing was run.");
                return 2;
            }
        }

        Spend spend = new Spend(tokenCap, c.maxCalls, cost);
        LLMClientFactory models = c.mock ? new MockModels() : env.models();
        Fixtures fx = fixtures;
        EvalRunner.Executors executors =
                beforeInitialize -> {
                    LoomScript fresh = new LoomLoader().load(scriptFile.toString());
                    ToolRegistry registry = new ToolRegistry();
                    if (c.lootFile != null && c.lootFile.exists())
                        new io.github.llm4j.loom.execution.LootLoader()
                                .loadIntoRegistry(c.lootFile.getAbsolutePath(), registry);
                    fx.apply(fresh, registry, c.mock);
                    HarnessExecutor e = new HarnessExecutor(fresh, registry, models);
                    e.setHumanInterface(m -> "yes");
                    if (c.mock)
                        e.setSimulateTasks(
                                true); // a free run must not save, send or pay: tasks that change
                    // things are described, not run
                    e.setBaseDir(scriptFile.getParent());
                    e.setEnvLookup(c.mock ? name -> "mock" : env.env());
                    e.setSecretStore(env.secrets());
                    e.setPromptCatalog(PromptSupport.catalog(fresh, scriptFile, env.prompts()));
                    e.setClock(env.clock());
                    e.setSleeper(env.sleeper());
                    if (c.prices != null)
                        e.setPriceTable(io.github.llm4j.budget.PriceTable.load(c.prices.toPath()));
                    if (!c.mock)
                        e.setBudgetOverrides(
                                spend.remainingTokens(),
                                spend.remainingCalls(),
                                spend.remainingCost());
                    beforeInitialize.accept(e);
                    e.initialize();
                    spend.track(e);
                    return e;
                };
        Map<String, RubricJudge> judges = new HashMap<>();
        List<io.github.llm4j.loom.eval.JudgeMeter> meters = new ArrayList<>();
        java.util.function.Function<String, RubricJudge> judgeFor =
                model -> {
                    if (c.mock) return null;
                    String name = c.judge != null ? c.judge : model;
                    return name == null
                            ? null
                            : judges.computeIfAbsent(
                                    name,
                                    n -> {
                                        try {
                                            // counted, so the judge's cost is part of the run's cost
                                            var meter =
                                                    new io.github.llm4j.loom.eval.JudgeMeter(
                                                            env.models().createClient(n), n);
                                            meters.add(meter);
                                            return RubricJudge.sampled(
                                                    RubricJudge.model(meter, n), c.judgeSamples);
                                        } catch (RuntimeException ex) {
                                            env.out().println("? no judge: " + ex.getMessage());
                                            return null;
                                        }
                                    });
                };
        Map<String, String> modelOf = new HashMap<>();
        script.getAgents().forEach(a -> modelOf.put(a.getName(), a.getModel()));
        String firstModel =
                script.getAgents().isEmpty() ? null : script.getAgents().get(0).getModel();
        EvalRunner runner = new EvalRunner(executors, judgeFor, modelOf::get, c.mock);

        env.out()
                .println(
                        (c.mock ? "🧪 Mock evaluation of " : "🧪 Evaluating ")
                                + c.script.getName()
                                + ": "
                                + scenarios
                                + " scenario"
                                + (scenarios == 1 ? "" : "s"));
        List<ScenarioResult> results = new ArrayList<>();
        int notRun = 0;
        String stopped = null;
        for (DatasetFolder.Target t : targets) {
            for (EvalScenario s : t.scenarios()) {
                if (stopped != null) {
                    notRun++;
                    continue;
                }
                for (int repetition = 1; repetition <= c.repetitions && stopped == null; repetition++) {
                    ScenarioResult r =
                            t.isAgent()
                                    ? runner.agent(t.file(), t.name(), s)
                                    : runner.workflow(
                                            t.file(),
                                            t.name(),
                                            parameters(script, t.name()),
                                            s,
                                            firstModel);
                    results.add(r);
                    env.out()
                            .println(
                                    EvalReport.mark(r.status())
                                            + " "
                                            + t.name()
                                            + " · "
                                            + r.label()
                                            + (c.repetitions > 1
                                                    ? "  (run " + repetition + " of " + c.repetitions + ")"
                                                    : ""));
                    if (runner.stoppedByLimit() || spend.exhausted())
                        stopped = "a limit was reached (" + spend.describe() + ")";
                }
            }
        }
        String runId = bundleRunId();
        Path bundleRoot = bundleRoot(scriptFile);
        Map<String, String> settings = new java.util.LinkedHashMap<>();
        settings.put(
                "dataset",
                DatasetFolder.locate(scriptFile, c.dataset == null ? null : c.dataset.toPath())
                        .toString());
        settings.put("weave", io.github.llm4j.BuildIdentity.ofClass(WeaveCLI.class).describe());
        settings.put("mode", c.mock ? "mock" : "real");
        settings.put("repetitions", String.valueOf(c.repetitions));
        settings.put("judge samples", String.valueOf(c.judgeSamples));
        settings.put("release gate", c.releaseGate ? "yes" : "no");
        settings.put("dataset version", identity.describe());
        if (c.baseline && c.allowVersionChange != null)
            settings.put("version change allowed", c.allowVersionChange);
        if (c.split != null) settings.put("split", c.split);
        if (heldOutLeftOutCount > 0) settings.put("held-out not run", String.valueOf(heldOutLeftOutCount));
        settings.put("bundle", bundleRoot.resolve("runs").resolve(runId).toString());
        EvalReport.Run run =
                new EvalReport.Run(
                        c.script.getName(),
                        c.mock,
                        results,
                        notRun,
                        stopped,
                        plan.dataset().dimensions(),
                        judgeFacts(meters, c),
                        settings,
                        reproduce(c),
                        List.of());
        if (c.baseline)
            run =
                    new EvalReport.Run(
                            run.script(), run.mock(), run.results(), run.notRun(), run.stoppedBecause(),
                            run.dimensions(), run.judge(), run.settings(), run.reproduce(),
                            io.github.llm4j.loom.eval.Baselines.compare(saved, run, identity));
        env.out().println();
        env.out().print(EvalSections.terminal(run));
        try {
            io.github.llm4j.loom.eval.EvalBundleWriter.write(run, bundleRoot, runId);
            env.out()
                    .println(
                            "Bundle for the eval4j dashboard: "
                                    + settings.get("bundle")
                                    + "  (render it: java -jar eval4j-report-<version>-cli.jar render "
                                    + bundleRoot
                                    + ")");
        } catch (RuntimeException e) {
            env.err().println("Warning: could not write the dashboard bundle: " + e.getMessage());
        }
        if (c.saveBaseline) {
            if (stopped != null || notRun > 0) {
                env.err().println("The baseline was not saved: the run did not finish, and a partial run is not a baseline.");
            } else if (project == null) {
                env.err().println("The baseline was not saved: no place for it beside the dataset.");
            } else {
                try {
                    Path file =
                            io.github.llm4j.loom.eval.Baselines.save(
                                    project, identity, run, c.approver, c.why, java.time.Instant.now());
                    env.out().println("Baseline saved: " + file + " (approved by " + c.approver + ").");
                } catch (RuntimeException e) {
                    env.err().println("The baseline was not saved: " + e.getMessage());
                }
            }
        }
        try {
            if (c.json != null) Files.writeString(c.json.toPath(), EvalReport.json(run));
            if (c.report != null) Files.writeString(c.report.toPath(), EvalReport.html(run));
        } catch (java.io.IOException e) {
            env.err().println("Error: could not write the results: " + e.getMessage());
            return 2;
        }
        return run.exitCode();
    }

    /** Where the dashboard bundle goes: beside a Maven project's other outputs, else next to the script. */
    /** The most times a case may be run in one evaluation (each is a paid run). */
    static final int MAX_REPETITIONS = 10;

    private static Path bundleRoot(Path scriptFile) {
        Path project = io.github.llm4j.loom.init.ProjectLayout.root(scriptFile);
        return Files.exists(project.resolve("pom.xml"))
                ? project.resolve("target").resolve("eval4j")
                : project.resolve("eval-runs");
    }

    /** A run id that sorts by start time and cannot collide with another run of the same second. */
    private static String bundleRunId() {
        return "eval-"
                + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                        .withZone(java.time.ZoneOffset.UTC)
                        .format(java.time.Instant.now())
                + "-"
                + java.util.UUID.randomUUID().toString().substring(0, 4);
    }

    /** What the judge used in this run, with its cost when prices are known; null when none ran. */
    private static EvalReport.JudgeFacts judgeFacts(
            List<io.github.llm4j.loom.eval.JudgeMeter> meters, EvalCommand c) {
        if (meters.isEmpty()) return null;
        io.github.llm4j.budget.PriceTable prices = null;
        try {
            if (c.prices != null) prices = io.github.llm4j.budget.PriceTable.load(c.prices.toPath());
        } catch (java.io.IOException | RuntimeException e) {
            prices = null; // the run's own check of the price file has already said so
        }
        String model = null;
        long calls = 0, in = 0, out = 0;
        java.math.BigDecimal cost = java.math.BigDecimal.ZERO;
        boolean priced = true;
        for (var meter : meters) {
            EvalReport.JudgeFacts f = meter.facts(prices);
            model = model == null ? f.model() : model + ", " + f.model();
            calls += f.calls();
            in += f.promptTokens();
            out += f.completionTokens();
            if (f.cost() == null) priced = false;
            else cost = cost.add(f.cost());
        }
        return new EvalReport.JudgeFacts(model, calls, in, out, priced ? cost : null, false);
    }

    /** The command that runs this evaluation again, from the options it was given. */
    private static String reproduce(EvalCommand c) {
        StringBuilder b = new StringBuilder("weave eval ").append(c.script.getName());
        if (c.mock) b.append(" --mock");
        if (c.repetitions != 1) b.append(" --repetitions ").append(c.repetitions);
        if (c.baseline) b.append(" --baseline");
        if (c.allowVersionChange != null) b.append(" --allow-version-change \"").append(c.allowVersionChange).append('"');
        if (c.releaseGate) b.append(" --release-gate");
        if (c.split != null) b.append(" --split ").append(c.split);
        if (c.judge != null) b.append(" --judge ").append(c.judge);
        if (c.judgeSamples != 1) b.append(" --judge-samples ").append(c.judgeSamples);
        if (c.agent != null) b.append(" --agent ").append(c.agent);
        if (c.workflow != null) b.append(" --workflow ").append(c.workflow);
        if (c.dataset != null) b.append(" --dataset ").append(c.dataset);
        if (c.maxTokens != null) b.append(" --max-tokens ").append(c.maxTokens);
        if (c.maxCalls != null) b.append(" --max-calls ").append(c.maxCalls);
        if (c.maxCost != null) b.append(" --max-cost ").append(c.maxCost);
        if (c.prices != null) b.append(" --prices ").append(c.prices);
        return b.toString();
    }

    static List<String> parameters(LoomScript script, String workflow) {
        return script.getWorkflows().stream()
                .filter(w -> w.getName().equals(workflow))
                .findFirst()
                .map(w -> List.copyOf(w.getParameters()))
                .orElse(List.of());
    }

    private static String firstName(LoomScript script) {
        return script.getAgents().isEmpty()
                ? "main"
                : script.getAgents().get(0).getName().toLowerCase();
    }

    // ── what has been spent, across scenarios ────────────────────────────────────────────────

    /** The caps of the whole evaluation: each scenario is given what is left. */
    private static final class Spend {
        private final Long tokens;
        private final Long calls;
        private final BigDecimal cost;
        private final List<HarnessExecutor> made = new ArrayList<>();

        Spend(Long tokens, Long calls, BigDecimal cost) {
            this.tokens = tokens;
            this.calls = calls;
            this.cost = cost;
        }

        void track(HarnessExecutor e) {
            made.add(e);
        }

        private long usedTokens() {
            return made.stream().mapToLong(e -> e.spend().total().tokens()).sum();
        }

        private long usedCalls() {
            return made.stream().mapToLong(e -> e.spend().total().calls()).sum();
        }

        private BigDecimal usedCost() {
            return made.stream()
                    .map(e -> e.spend().total().cost())
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        Long remainingTokens() {
            return tokens == null ? null : Math.max(1, tokens - usedTokens());
        }

        Long remainingCalls() {
            return calls == null ? null : Math.max(1, calls - usedCalls());
        }

        BigDecimal remainingCost() {
            return cost == null ? null : cost.subtract(usedCost()).max(new BigDecimal("0.000001"));
        }

        boolean exhausted() {
            return (tokens != null && usedTokens() >= tokens)
                    || (calls != null && usedCalls() >= calls)
                    || (cost != null && usedCost().compareTo(cost) >= 0);
        }

        String describe() {
            List<String> parts = new ArrayList<>();
            if (tokens != null) parts.add(usedTokens() + " of " + tokens + " tokens");
            if (calls != null) parts.add(usedCalls() + " of " + calls + " calls");
            if (cost != null) parts.add("$" + usedCost() + " of $" + cost);
            return String.join(", ", parts);
        }
    }

    // ── --init ───────────────────────────────────────────────────────────────────────────────

    private static int init(LoomScript script, Path dir, WeaveEnv env) {
        try {
            Files.createDirectories(dir);
            List<String> made = new ArrayList<>();
            List<String> kept = new ArrayList<>();
            for (var agent : script.getAgents()) {
                String name = agent.getName();
                write(
                        dir.resolve(name.toLowerCase() + ".yaml"),
                        """
                        # Cases for the agent %1$s. Replace the text with real requests and what a good answer does.
                        - id: %2$s-001
                          name: First case for %1$s
                          input: "Replace this with a realistic request for %1$s."
                          rubric:
                            - "Replace this with something a good answer must do."
                        """
                                .formatted(name, name.toLowerCase()),
                        made,
                        kept);
            }
            var workflows = script.getWorkflows();
            if (!workflows.isEmpty()) {
                String w =
                        workflows.stream().anyMatch(x -> x.getName().equals("Main"))
                                ? "Main"
                                : workflows.get(0).getName();
                write(
                        dir.resolve("workflow.yaml"),
                        """
                        # Cases for the whole workflow %1$s (the input goes to its first parameter).
                        - id: workflow-001
                          name: First end-to-end case
                          input: "Replace this with a realistic request."
                          expect:
                            - "Replace this with what the run should have done, for example: the approval step ran before anything was sent."
                        """
                                .formatted(w),
                        made,
                        kept);
            }
            write(
                    dir.resolve("dataset.yaml"),
                    """
                    # The quality dimensions this dataset uses, with what each means. A report shows every one of them, even when nothing evaluated it.
                    dimensions:
                      correctness: The answer is right
                      safety: It does nothing harmful and respects its limits
                    """,
                    made,
                    kept);
            made.forEach(f -> env.out().println("created " + dir.resolve(f)));
            kept.forEach(
                    f -> env.out().println("kept    " + dir.resolve(f) + " (it already exists)"));
            env.out()
                    .println(
                            "Next: edit the cases, then run: weave eval <script> --check, then weave eval <script> --mock");
            return 0;
        } catch (java.io.IOException e) {
            env.err().println("Error: could not create the dataset: " + e.getMessage());
            return 2;
        }
    }

    private static void write(Path file, String content, List<String> made, List<String> kept)
            throws java.io.IOException {
        if (Files.exists(file)) {
            kept.add(file.getFileName().toString());
            return;
        }
        Files.writeString(file, content);
        made.add(file.getFileName().toString());
    }

    /**
     * Who grades the plain-sentence checks and with which key, said before anything is spent: the
     * agent's own model unless {@code --judge} names another, so a person is never surprised that
     * the same model (and key) marks its own work, or that a second provider's key is needed.
     */
    static String judgeLine(
            LoomScript script, List<DatasetFolder.Target> targets, String judge, WeaveEnv env) {
        java.util.Set<String> models = new java.util.TreeSet<>();
        for (DatasetFolder.Target t : targets) {
            boolean judged =
                    t.scenarios().stream()
                            .anyMatch(
                                    s -> !s.rubricLines().isEmpty() || !s.expectLines().isEmpty());
            if (!judged) continue;
            if (judge != null) models.add(judge);
            else if (t.isAgent())
                script.getAgents().stream()
                        .filter(a -> a.getName().equals(t.name()))
                        .findFirst()
                        .ifPresent(a -> models.add(a.getModel()));
            else script.getAgents().forEach(a -> models.add(a.getModel()));
        }
        List<String> parts = new java.util.ArrayList<>();
        for (String m : models) {
            String problem = m == null ? null : env.models().problem(m);
            java.util.regex.Matcher key =
                    problem == null
                            ? null
                            : java.util.regex.Pattern.compile("needs ([A-Z0-9_]+)")
                                    .matcher(problem);
            String keyName = key != null && key.find() ? key.group(1) : keyFor(m);
            parts.add(
                    m
                            + (keyName == null
                                    ? ""
                                    : " using "
                                            + keyName
                                            + (problem != null
                                                    ? " (NOT FOUND: " + problem + ")"
                                                    : " (found)")));
        }
        return "Judge: "
                + String.join("; ", parts)
                + (judge == null
                        ? ". The agent's own model grades its own answers, with the same key, and those calls count toward the cost; --judge <model> names another (safer)."
                        : ".");
    }

    private static String keyFor(String model) {
        if (model == null) return null;
        String m = model.toLowerCase(java.util.Locale.ROOT);
        if (m.startsWith("gemini")) return "GEMINI_API_KEY";
        if (m.startsWith("claude")) return "ANTHROPIC_API_KEY";
        if (m.startsWith("sarvam")) return "SARVAM_API_KEY";
        return io.github.llm4j.provider.openai.Vendor.ofModel(model)
                .map(r -> r.vendor().keyEnv())
                .orElse(null);
    }
}
