package io.github.llm4j.loom.cli;

import io.github.llm4j.LLMClient;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.eval.DatasetFolder;
import io.github.llm4j.loom.eval.EvalReport;
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
 * {@code weave eval}: runs the golden dataset of a script against it and says what passed, what failed and what nothing judged. Needs no Java:
 * the dataset is YAML files in {@code eval/golden}. {@code --mock} runs on a model that costs nothing, to check the wiring; a real run needs a cap.
 */
@Command(name = "eval", description = "Runs a script's golden dataset (eval/golden/*.yaml) against it and reports passed, failed and unjudged. --mock costs nothing; --check validates the dataset without calling a model; --init creates one.")
final class EvalCommand implements Callable<Integer> {

    /** What a real run is limited to when no limit is given: a message says so. */
    static final long DEFAULT_MAX_TOKENS = 500_000;

    @Parameters(index = "0", description = "The .loom script.")
    File script;

    @Option(names = {"-l", "--loot"}, description = "The .loot tool mapping file, for a script that uses your own tools (a real run calls them; --mock does not).")
    File lootFile;

    @Option(names = "--dataset", paramLabel = "<dir>", description = "The folder of dataset files (default: eval/golden beside the script).")
    File dataset;

    @Option(names = "--agent", description = "Only this agent's scenarios.")
    String agent;

    @Option(names = "--workflow", description = "Only this workflow's scenarios (and which workflow workflow.yaml is for).")
    String workflow;

    @Option(names = "--mock", description = "Run on a model that costs nothing and answers every step with a fixed, well-formed reply: it checks the wiring, not the quality.")
    boolean mock;

    @Option(names = "--check", description = "Only validate the dataset: names, fields, dimensions, fixtures. No model is called.")
    boolean check;

    @Option(names = "--init", description = "Create eval/golden with one starter scenario per agent and one for the workflow. Never overwrites a file.")
    boolean init;

    @Option(names = "--judge", paramLabel = "<model>", description = "The model that judges rubric and expect lines (default: the agent's own model).")
    String judge;

    @Option(names = "--max-tokens", description = "Cap the tokens of a real run (default " + DEFAULT_MAX_TOKENS + ").")
    Long maxTokens;

    @Option(names = "--max-calls", description = "Cap the model calls of a real run.")
    Long maxCalls;

    @Option(names = "--max-cost", description = "Cap the cost of a real run, e.g. 0.50 (needs --prices).")
    String maxCost;

    @Option(names = "--prices", description = "Price table: lines of 'model = input / output' per million tokens.")
    File prices;

    @Option(names = {"-y", "--yes"}, description = "Do not ask before a real run.")
    boolean yes;

    @Option(names = "--json", paramLabel = "<file>", description = "Also write the results as JSON.")
    File json;

    @Option(names = "--report", paramLabel = "<file>", description = "Also write the results as a single HTML page.")
    File report;

    @Mixin
    PromptOptions promptOptions = new PromptOptions();

    @Mixin
    SecretOptions secrets = new SecretOptions();

    @Mixin
    EnvFileOptions envFile = new EnvFileOptions();

    @Override
    public Integer call() {
        WeaveEnv env = envFile.apply(WeaveEnv.system(), script.toPath());
        env = env == null ? null : secrets.apply(env, Prompts.console());
        env = env == null ? null : promptOptions.apply(env);
        return env == null ? 2 : eval(this, env);
    }

    static int eval(EvalCommand c, WeaveEnv env) {
        if (c.check && c.init) {
            env.err().println("Error: --check and --init cannot be used together.");
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
        Fixtures fixtures = Fixtures.none();
        try {
            fixtures = Fixtures.read(dir);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }
        if (!DatasetFolder.exists(dir)) {
            env.err().println("Error: there is no dataset folder at " + dir + ". Create one with: weave eval " + c.script + " --init");
            return 2;
        }
        if (plan.targets().isEmpty() && problems.isEmpty()) problems.add(dir + " has no dataset files (a file per agent or workflow, such as " + firstName(script) + ".yaml)");
        if (!problems.isEmpty()) {
            problems.forEach(p -> env.out().println("✗ " + p));
            env.out().println(problems.size() + " problem" + (problems.size() == 1 ? "" : "s") + " in " + dir);
            return 2;
        }
        if (c.check) {
            long files = plan.targets().size();
            env.out().println("✓ " + dir.getFileName() + ": " + plan.scenarioCount() + " scenario" + (plan.scenarioCount() == 1 ? "" : "s") + " in " + files + " file" + (files == 1 ? "" : "s")
                    + (fixtures.names().isEmpty() ? "" : ", fixtures for " + String.join(", ", fixtures.names())) + ", ready to run");
            return 0;
        }
        return run(c, env, script, scriptFile, plan, fixtures);
    }

    // ── running ──────────────────────────────────────────────────────────────────────────────

    private static int run(EvalCommand c, WeaveEnv env, LoomScript script, Path scriptFile, DatasetFolder.Plan plan, Fixtures fixtures) {
        List<DatasetFolder.Target> targets = new ArrayList<>();
        for (DatasetFolder.Target t : plan.targets()) {
            if (c.agent != null && !(t.isAgent() && t.name().equalsIgnoreCase(c.agent))) continue;
            if (c.workflow != null && !(!t.isAgent() && t.name().equalsIgnoreCase(c.workflow))) continue;
            targets.add(t);
        }
        if (targets.isEmpty()) {
            env.err().println("Error: no scenarios match" + (c.agent != null ? " --agent " + c.agent : "") + (c.workflow != null ? " --workflow " + c.workflow : "")
                    + ". The dataset covers: " + String.join(", ", plan.targets().stream().map(DatasetFolder.Target::name).toList()) + ".");
            return 2;
        }
        BigDecimal cost = null;
        if (c.maxCost != null) {
            if (c.prices == null) {
                env.err().println("Error: --max-cost needs --prices <file> (lines of 'model = input / output' per million tokens).");
                return 2;
            }
            try {
                cost = new BigDecimal(c.maxCost.strip().replaceFirst("^\\$", ""));
            } catch (NumberFormatException e) {
                env.err().println("Error: --max-cost takes a number such as 0.50, not " + c.maxCost + ".");
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
        long judgedLines = targets.stream().flatMap(t -> t.scenarios().stream()).mapToLong(s -> s.rubricLines().size() + s.expectLines().size()).sum();
        Long tokenCap = c.mock ? null : (c.maxTokens != null || c.maxCalls != null || cost != null ? c.maxTokens : Long.valueOf(DEFAULT_MAX_TOKENS));
        if (!c.mock) {
            if (c.maxTokens == null && c.maxCalls == null && cost == null) {
                env.out().println("No limit was given, so a real run is capped at " + DEFAULT_MAX_TOKENS + " tokens (--max-tokens, --max-calls or --max-cost change that).");
            }
            env.out().println("This will make real model calls: " + scenarios + " scenario" + (scenarios == 1 ? "" : "s") + ", at least one agent call each, and up to "
                    + judgedLines + " judge call" + (judgedLines == 1 ? "" : "s") + ". Use --mock to check the wiring for free.");
            if (!c.yes && !env.human().promptHuman("Run it? (yes/no)").strip().toLowerCase().startsWith("y")) {
                env.out().println("Cancelled; nothing was run.");
                return 2;
            }
        }

        Spend spend = new Spend(tokenCap, c.maxCalls, cost);
        LLMClientFactory models = c.mock ? new MockModels() : env.models();
        Fixtures fx = fixtures;
        EvalRunner.Executors executors = beforeInitialize -> {
            LoomScript fresh = new LoomLoader().load(scriptFile.toString());
            ToolRegistry registry = new ToolRegistry();
            if (c.lootFile != null && c.lootFile.exists()) new io.github.llm4j.loom.execution.LootLoader().loadIntoRegistry(c.lootFile.getAbsolutePath(), registry);
            fx.apply(fresh, registry, c.mock);
            HarnessExecutor e = new HarnessExecutor(fresh, registry, models);
            e.setHumanInterface(m -> "yes");
            e.setBaseDir(scriptFile.getParent());
            e.setEnvLookup(c.mock ? name -> "mock" : env.env());
            e.setSecretStore(env.secrets());
            e.setPromptCatalog(PromptSupport.catalog(fresh, scriptFile, env.prompts()));
            e.setClock(env.clock());
            e.setSleeper(env.sleeper());
            if (c.prices != null) e.setPriceTable(io.github.llm4j.budget.PriceTable.load(c.prices.toPath()));
            if (!c.mock) e.setBudgetOverrides(spend.remainingTokens(), spend.remainingCalls(), spend.remainingCost());
            beforeInitialize.accept(e);
            e.initialize();
            spend.track(e);
            return e;
        };
        Map<String, RubricJudge> judges = new HashMap<>();
        java.util.function.Function<String, RubricJudge> judgeFor = model -> {
            if (c.mock) return null;
            String name = c.judge != null ? c.judge : model;
            return name == null ? null : judges.computeIfAbsent(name, n -> {
                try {
                    LLMClient client = env.models().createClient(n);
                    return RubricJudge.model(client, n);
                } catch (RuntimeException ex) {
                    env.out().println("? no judge: " + ex.getMessage());
                    return null;
                }
            });
        };
        Map<String, String> modelOf = new HashMap<>();
        script.getAgents().forEach(a -> modelOf.put(a.getName(), a.getModel()));
        String firstModel = script.getAgents().isEmpty() ? null : script.getAgents().get(0).getModel();
        EvalRunner runner = new EvalRunner(executors, judgeFor, modelOf::get, c.mock);

        env.out().println((c.mock ? "🧪 Mock evaluation of " : "🧪 Evaluating ") + c.script.getName() + ": " + scenarios + " scenario" + (scenarios == 1 ? "" : "s"));
        List<ScenarioResult> results = new ArrayList<>();
        int notRun = 0;
        String stopped = null;
        for (DatasetFolder.Target t : targets) {
            for (EvalScenario s : t.scenarios()) {
                if (stopped != null) {
                    notRun++;
                    continue;
                }
                ScenarioResult r = t.isAgent()
                        ? runner.agent(t.file(), t.name(), s)
                        : runner.workflow(t.file(), t.name(), firstParameter(script, t.name()), s, firstModel);
                results.add(r);
                env.out().println(EvalReport.mark(r.status()) + " " + t.name() + " · " + r.label());
                if (runner.stoppedByLimit() || spend.exhausted()) stopped = "a limit was reached (" + spend.describe() + ")";
            }
        }
        EvalReport.Run run = new EvalReport.Run(c.script.getName(), c.mock, results, notRun, stopped, plan.dataset().dimensions());
        env.out().println();
        env.out().print(EvalReport.detail(run));
        env.out().println(EvalReport.summary(run));
        try {
            if (c.json != null) Files.writeString(c.json.toPath(), EvalReport.json(run));
            if (c.report != null) Files.writeString(c.report.toPath(), EvalReport.html(run));
        } catch (java.io.IOException e) {
            env.err().println("Error: could not write the results: " + e.getMessage());
            return 2;
        }
        return run.exitCode();
    }

    private static String firstParameter(LoomScript script, String workflow) {
        return script.getWorkflows().stream().filter(w -> w.getName().equals(workflow)).findFirst()
                .map(WorkflowDef::getParameters).filter(p -> !p.isEmpty()).map(p -> p.get(0)).orElse(null);
    }

    private static String firstName(LoomScript script) {
        return script.getAgents().isEmpty() ? "main" : script.getAgents().get(0).getName().toLowerCase();
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
            return made.stream().map(e -> e.spend().total().cost()).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
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
            return (tokens != null && usedTokens() >= tokens) || (calls != null && usedCalls() >= calls) || (cost != null && usedCost().compareTo(cost) >= 0);
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
                write(dir.resolve(name.toLowerCase() + ".yaml"), """
                        # Cases for the agent %1$s. Replace the text with real requests and what a good answer does.
                        - id: %2$s-001
                          name: First case for %1$s
                          input: "Replace this with a realistic request for %1$s."
                          rubric:
                            - "Replace this with something a good answer must do."
                        """.formatted(name, name.toLowerCase()), made, kept);
            }
            var workflows = script.getWorkflows();
            if (!workflows.isEmpty()) {
                String w = workflows.stream().anyMatch(x -> x.getName().equals("Main")) ? "Main" : workflows.get(0).getName();
                write(dir.resolve("workflow.yaml"), """
                        # Cases for the whole workflow %1$s (the input goes to its first parameter).
                        - id: workflow-001
                          name: First end-to-end case
                          input: "Replace this with a realistic request."
                          expect:
                            - "Replace this with what the run should have done, for example: the approval step ran before anything was sent."
                        """.formatted(w), made, kept);
            }
            write(dir.resolve("dataset.yaml"), """
                    # The quality dimensions this dataset uses, with what each means. A report shows every one of them, even when nothing evaluated it.
                    dimensions:
                      correctness: The answer is right
                      safety: It does nothing harmful and respects its limits
                    """, made, kept);
            made.forEach(f -> env.out().println("created " + dir.resolve(f)));
            kept.forEach(f -> env.out().println("kept    " + dir.resolve(f) + " (it already exists)"));
            env.out().println("Next: edit the cases, then run: weave eval <script> --check, then weave eval <script> --mock");
            return 0;
        } catch (java.io.IOException e) {
            env.err().println("Error: could not create the dataset: " + e.getMessage());
            return 2;
        }
    }

    private static void write(Path file, String content, List<String> made, List<String> kept) throws java.io.IOException {
        if (Files.exists(file)) {
            kept.add(file.getFileName().toString());
            return;
        }
        Files.writeString(file, content);
        made.add(file.getFileName().toString());
    }
}
