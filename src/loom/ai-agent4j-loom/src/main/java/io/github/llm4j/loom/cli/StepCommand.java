package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.debug.Stepper;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.travel.ObserveReplay;
import io.github.llm4j.loom.travel.ScriptHash;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave step}: runs a workflow one statement at a time. Before each statement the run waits;
 * the variables, the statement and where it is in the graph are shown, and the person says go on,
 * go in, go out, go back, change a variable or answer for a model. Nothing has an effect: models
 * are mock (or live, under a cap, when asked), classifiers answer their first label, and tools that
 * change things are only described.
 */
@Command(
        name = "step",
        description =
                "Runs a workflow one statement at a time, with breakpoints, the variables and the way back. Nothing has an effect.")
final class StepCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "The .loom script.")
    File script;

    @Option(
            names = {"-w", "--workflow"},
            description = "The workflow to run (default: the first).")
    String workflow;

    @Option(
            names = {"-i", "--input"},
            description = "An input of the workflow: name=value (repeatable).")
    Map<String, String> inputs = new LinkedHashMap<>();

    @Option(
            names = "--journal",
            description =
                    "A run directory to keep the steps in. If it holds a run, stepping starts from what that run did; on quit what was done is written there, so weave why and weave resume can use it.")
    File journal;

    @Option(
            names = "--break",
            description =
                    "A breakpoint to start with: a line, n3, an Agent, or \"when <condition>\" (repeatable).")
    List<String> breaks = new ArrayList<>();

    @Option(names = "--mask", description = "A variable never shown (repeatable).")
    Set<String> masked = new LinkedHashSet<>();

    @Option(
            names = "--effects",
            description = "simulate (the only mode here: nothing is sent or written).",
            defaultValue = "simulate")
    String effects;

    @Option(
            names = "--models",
            description =
                    "mock (default: every reply is a fixed, well-formed one) or live (ask the real models, under --max-cost or --max-tokens and --yes; tools that change things are still only described).",
            defaultValue = "mock")
    String models;

    @Option(names = "--max-cost", description = "With --models live: the most it may cost.")
    java.math.BigDecimal maxCost;

    @Option(names = "--max-tokens", description = "With --models live: the most tokens it may use.")
    Long maxTokens;

    @Option(names = "--prices", description = "The price table for --max-cost.")
    File prices;

    @Option(names = "--yes", description = "With --models live: go ahead and spend, up to the cap.")
    boolean yes;

    @Option(names = "--record", description = "Write the commands of this session to a file.")
    File record;

    @Option(
            names = "--replay",
            description = "Run the commands of a recorded session instead of reading them.")
    File replay;

    @Override
    public Integer call() {
        return step(
                this,
                WeaveEnv.system(),
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
    }

    static int step(StepCommand c, WeaveEnv env, BufferedReader stdin) {
        if (!c.effects.equals("simulate")) {
            env.err()
                    .println(
                            "Error: weave step only simulates: nothing is sent or written. --effects takes simulate.");
            return 2;
        }
        if (!List.of("mock", "live").contains(c.models)) {
            env.err().println("Error: --models takes mock or live.");
            return 2;
        }
        ObserveReplay.Live live = null;
        if (c.models.equals("live")) {
            if (c.maxCost == null && c.maxTokens == null) {
                env.err()
                        .println(
                                "Error: --models live spends money: give --max-cost (with --prices) or --max-tokens.");
                return 2;
            }
            if (c.maxCost != null && c.prices == null) {
                env.err().println("Error: --max-cost needs --prices to turn tokens into money.");
                return 2;
            }
            if (!c.yes) {
                env.err()
                        .println(
                                "Error: --models live may spend up to "
                                        + (c.maxCost != null ? c.maxCost + " " : "")
                                        + (c.maxTokens != null ? c.maxTokens + " tokens " : "")
                                        + "calling real models. Add --yes to go ahead.");
                return 2;
            }
            try {
                live =
                        new ObserveReplay.Live(
                                env.models(),
                                c.maxTokens,
                                c.maxCost,
                                c.prices == null
                                        ? null
                                        : io.github.llm4j.budget.PriceTable.load(c.prices.toPath()),
                                env.env());
            } catch (Exception e) {
                env.err().println("Error: cannot read the prices: " + e.getMessage());
                return 2;
            }
        }
        if (!c.script.isFile()) {
            env.err().println("Error: the script " + c.script + " is not there.");
            return 2;
        }
        Path scriptPath = c.script.toPath().toAbsolutePath().normalize();
        String workflow = c.workflow;
        Map<String, String> inputs = new LinkedHashMap<>(c.inputs);
        RunJournal start = RunJournal.inMemory();
        Path dir = c.journal == null ? null : c.journal.toPath().toAbsolutePath().normalize();
        if (dir != null && Files.exists(dir.resolve(RunSpec.FILE))) {
            RunSpec spec;
            try {
                spec = RunSpec.read(dir);
            } catch (RuntimeException e) {
                env.err().println("Error: " + e.getMessage());
                return 2;
            }
            String recorded = RunSpec.scriptHashOf(dir);
            if (recorded != null && !recorded.equals(ScriptHash.of(scriptPath))) {
                env.err()
                        .println(
                                "Error: "
                                        + scriptPath
                                        + " has changed since the run in "
                                        + dir
                                        + " began, so stepping through it would not be stepping through this script.");
                return 2;
            }
            if (workflow == null) workflow = spec.workflow();
            if (inputs.isEmpty()) inputs.putAll(spec.inputs());
            new FileRunJournal(dir.resolve(TravelCommands.JOURNAL)).all().forEach(start::put);
        }
        if (workflow == null) {
            try {
                var loaded =
                        new io.github.llm4j.loom.execution.LoomLoader().load(scriptPath.toString());
                if (loaded.getWorkflows().isEmpty()) {
                    env.err().println("Error: " + scriptPath + " has no workflow.");
                    return 2;
                }
                workflow = loaded.getWorkflows().get(0).getName();
            } catch (Exception e) {
                env.err().println("Error: " + (e.getMessage() == null ? e : e.getMessage()));
                return 2;
            }
        }
        BufferedReader in = stdin;
        boolean replaying = c.replay != null;
        if (replaying) {
            try {
                in = Files.newBufferedReader(c.replay.toPath(), StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                env.err()
                        .println(
                                "Error: cannot read the session "
                                        + c.replay
                                        + ": "
                                        + e.getMessage());
                return 2;
            }
        }
        PrintStream recorder = null;
        try {
            if (c.record != null)
                recorder =
                        new PrintStream(
                                Files.newOutputStream(c.record.toPath()),
                                true,
                                StandardCharsets.UTF_8);
            try (Stepper stepper =
                    Stepper.open(
                            new Stepper.Config(
                                    scriptPath,
                                    workflow,
                                    inputs,
                                    new LinkedHashSet<>(c.masked),
                                    start,
                                    live))) {
                for (String b : c.breaks) {
                    var bp = stepper.breakAt(b);
                    env.out().println("Breakpoint " + bp.id() + ": " + bp.describe());
                }
                new StepSession(
                                stepper,
                                scriptPath,
                                workflow,
                                inputs,
                                c.masked,
                                env.out(),
                                recorder,
                                replaying)
                        .run(in);
                if (dir != null) save(dir, scriptPath, workflow, inputs, stepper.journal(), env);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            env.err().println("Error: " + (e.getMessage() == null ? e : e.getMessage()));
            return 2;
        } finally {
            if (recorder != null) recorder.close();
            if (replaying)
                try {
                    in.close();
                } catch (java.io.IOException ignored) {
                    // read-only
                }
        }
        return 0;
    }

    private static void save(
            Path dir,
            Path script,
            String workflow,
            Map<String, String> inputs,
            Map<String, RunJournal.Entry> entries,
            WeaveEnv env) {
        if (!Files.exists(dir.resolve(RunSpec.FILE)))
            new RunSpec(
                            script.toString(),
                            null,
                            workflow,
                            inputs,
                            null,
                            null,
                            null,
                            null,
                            null,
                            false,
                            null,
                            true,
                            null,
                            null,
                            null)
                    .write(dir);
        FileRunJournal into = new FileRunJournal(dir.resolve(TravelCommands.JOURNAL));
        entries.forEach(into::put);
        env.err().println("Kept the steps in " + dir + " (weave why " + dir + ").");
    }
}
