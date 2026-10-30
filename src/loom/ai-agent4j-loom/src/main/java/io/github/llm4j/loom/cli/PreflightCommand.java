package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.BuildIdentity;
import io.github.llm4j.budget.PriceTable;
import io.github.llm4j.context.ContextWindows;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.eval.DatasetFolder;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.init.ProjectLayout;
import io.github.llm4j.loom.preflight.CanaryMockModels;
import io.github.llm4j.loom.preflight.PreflightRunner;
import io.github.llm4j.loom.prompt.PromptSupport;
import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave preflight}: one cheap live check per agent between "the script checks clean" and
 * "build the dataset". It lists what it will send, asks, stays under its own cap and records the
 * result in {@code .loom/preflight.json}, which {@code weave next} reads.
 */
@Command(
        name = "preflight",
        mixinStandardHelpOptions = true,
        description =
                "Sends one small probe per agent through its real prompts and checks: the key, the model, one whole answer, that the shared prompt reaches the model, sources for grounded agents, price and window. Asks first and stays under a small cap. --mock does it with no model.")
final class PreflightCommand implements Callable<Integer> {
    /** The default cap on what the probes may cost, in the currency of the price file. */
    static final String DEFAULT_MAX_COST = "0.05";

    /** Without prices a cost cap cannot be applied, so the probes are capped in tokens instead. */
    static final long DEFAULT_MAX_TOKENS = 20_000;

    private static final String DEFAULT_PROBE = "Say hello in one short sentence.";

    @Parameters(index = "0", paramLabel = "<script>", description = "The .loom script.")
    File script;

    @Option(names = "--input", paramLabel = "<text>", description = "The probe input (default: the agent's first dataset case, else a greeting).")
    String input;

    @Option(names = "--max-cost", paramLabel = "<amount>", description = "Stop when the probes have cost this much (needs --prices). Default 0.05.")
    String maxCost;

    @Option(names = "--max-tokens", paramLabel = "<n>", description = "Stop when the probes have used this many tokens (default 20000 when there are no prices).")
    Long maxTokens;

    @Option(names = "--prices", paramLabel = "<file>", description = "Price table: lines of 'model = input / output' per million tokens.")
    File prices;

    @Option(names = "--mock", description = "Run the same checks on models that cost nothing.")
    boolean mock;

    @Option(names = {"-y", "--yes"}, description = "Do not ask before sending the probes.")
    boolean yes;

    @Option(names = "--format", paramLabel = "text|json", description = "The report as text (default) or JSON.", defaultValue = "text")
    String format;

    @Override
    public Integer call() {
        return preflight(this, WeaveEnv.system());
    }

    static int preflight(PreflightCommand c, WeaveEnv env) {
        Path scriptFile = c.script.toPath().toAbsolutePath();
        LoomScript parsed;
        try {
            parsed = new LoomLoader().load(scriptFile.toString());
        } catch (Exception e) {
            env.err().println("Error: " + c.script + " could not be read: " + e.getMessage());
            return 2;
        }
        if (parsed.getAgents().isEmpty()) {
            env.err().println("Error: the script has no agents to probe.");
            return 2;
        }
        BigDecimal costCap = null;
        PriceTable priceTable = null;
        if (c.prices != null) {
            try {
                priceTable = PriceTable.load(c.prices.toPath());
            } catch (Exception e) {
                env.err().println("Error: the price file could not be read: " + e.getMessage());
                return 2;
            }
        }
        if (c.maxCost != null) {
            if (priceTable == null) {
                env.err().println("Error: --max-cost needs --prices <file>.");
                return 2;
            }
            try {
                costCap = new BigDecimal(c.maxCost.strip().replaceFirst("^\\$", ""));
            } catch (NumberFormatException e) {
                env.err().println("Error: --max-cost takes a number such as 0.05, not " + c.maxCost + ".");
                return 2;
            }
        } else if (priceTable != null && !c.mock) {
            costCap = new BigDecimal(DEFAULT_MAX_COST);
        }
        Long tokenCap = c.maxTokens != null ? c.maxTokens : (costCap == null && !c.mock ? Long.valueOf(DEFAULT_MAX_TOKENS) : null);
        if ((tokenCap != null && tokenCap <= 0) || (costCap != null && costCap.signum() <= 0)) {
            env.err().println("Error: the cap must be positive.");
            return 2;
        }
        if (!c.format.equals("text") && !c.format.equals("json")) {
            env.err().println("Error: --format takes text or json, got " + c.format + ".");
            return 2;
        }

        List<DatasetFolder.Target> targets = datasetTargets(parsed, scriptFile);
        java.util.function.Function<String, String> probeFor = agent -> c.input != null ? c.input : firstCase(targets, agent);
        if (!c.mock && !c.yes) {
            env.out().println("This will send one small probe to each of the model providers below, through each agent's real prompts:");
            for (var agent : parsed.getAgents())
                env.out().println("  " + agent.getName() + " (" + agent.getModel() + "): " + describe(probeFor.apply(agent.getName()), c.input != null));
            env.out().println("Cap: " + (costCap != null ? costCap.toPlainString() + " in cost" : tokenCap + " tokens") + ". Nothing else is sent, and no answer text is stored.");
            String answer = env.human().promptHuman("Send the probes? (yes/no)");
            if (answer == null || !answer.strip().toLowerCase(java.util.Locale.ROOT).startsWith("y")) {
                env.out().println("Cancelled; nothing was sent.");
                return 2;
            }
        }
        LLMClientFactory models = c.mock ? new CanaryMockModels() : env.models();
        PriceTable prices = priceTable;
        PreflightRunner.Executors executors =
                (instruction, tokensLeft, costLeft) -> {
                    try {
                        LoomScript fresh = new LoomLoader().load(scriptFile.toString());
                        io.github.llm4j.loom.prompt.PromptCatalog catalog = PromptSupport.catalog(fresh, scriptFile, env.prompts());
                        if (instruction != null) {
                            String base = fresh.getSharedPrompt();
                            if (base == null && fresh.getSharedPromptRef() != null && catalog != null)
                                base = catalog.resolve(fresh.getSharedPromptRef()).map(r -> r.text()).orElse(null);
                            // in memory only: the canary is never written anywhere
                            fresh.setSharedPrompt((base == null ? "" : base.strip() + "\n\n") + instruction, null, fresh.getSharedPromptLine());
                        }
                        HarnessExecutor e = new HarnessExecutor(fresh, new ToolRegistry(), models);
                        e.setHumanInterface(m -> "yes");
                        e.setBaseDir(scriptFile.getParent());
                        e.setEnvLookup(c.mock ? name -> "mock" : env.env());
                        e.setSecretStore(env.secrets());
                        e.setPromptCatalog(catalog);
                        e.setClock(env.clock());
                        e.setSleeper(env.sleeper());
                        if (prices != null) e.setPriceTable(prices);
                        if (!c.mock) e.setBudgetOverrides(tokensLeft, null, costLeft);
                        e.initialize();
                        return e;
                    } catch (java.io.IOException ex) {
                        throw new IllegalStateException(ex.getMessage(), ex);
                    }
                };
        PreflightRunner.Report report =
                PreflightRunner.run(
                        parsed, executors, models, probeFor, tokenCap, costCap, prices,
                        ContextWindows.defaults(), env.clock(), BuildIdentity.ofClass(WeaveCLI.class).describe());
        String json = withScriptHash(report.toJson(), scriptFile, c.mock);
        try {
            Path record = ProjectLayout.root(scriptFile).resolve(".loom").resolve("preflight.json");
            Files.createDirectories(record.getParent());
            Files.writeString(record, json);
        } catch (java.io.IOException e) {
            env.err().println("Warning: could not write .loom/preflight.json: " + e.getMessage());
        }
        env.out().print(c.format.equals("json") ? json + "\n" : report.toText());
        return report.exitCode();
    }

    /** Adds what {@code weave next} needs to tell a stale record from a current one. */
    private static String withScriptHash(String json, Path scriptFile, boolean mock) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = (ObjectNode) mapper.readTree(json);
            node.put("script", scriptFile.getFileName().toString());
            node.put("scriptHash", hash(Files.readString(scriptFile)));
            node.put("mock", mock);
            return mapper.writeValueAsString(node);
        } catch (java.io.IOException e) {
            return json;
        }
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<DatasetFolder.Target> datasetTargets(LoomScript script, Path scriptFile) {
        try {
            Path dir = DatasetFolder.locate(scriptFile, null);
            return DatasetFolder.exists(dir) ? DatasetFolder.plan(script, dir, null).targets() : List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static String firstCase(List<DatasetFolder.Target> targets, String agent) {
        for (var t : targets)
            if (t.isAgent() && t.name().equalsIgnoreCase(agent))
                for (var s : t.scenarios()) if (s.input() != null && !s.input().isBlank()) return s.input();
        return DEFAULT_PROBE;
    }

    private static String describe(String probe, boolean given) {
        return (given ? "your --input" : probe.equals(DEFAULT_PROBE) ? "a greeting" : "its first dataset case") + " as the probe";
    }
}
