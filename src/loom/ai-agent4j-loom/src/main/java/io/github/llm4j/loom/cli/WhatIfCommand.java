package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.explain.Why;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.travel.ScriptHash;
import io.github.llm4j.loom.whatif.WhatIf;
import io.github.llm4j.loom.whatif.WhatIfRenderer;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * {@code weave whatif}: what would a past run have done if one thing had been different. The run's
 * journal is copied and cut back to a step; a variable, a model or classifier answer, a person's
 * answer, a failure or a tool result is changed there; the script runs forward over the copy with
 * no model, tool, task or person. A model's answer is reused only where its question is unchanged,
 * and never guessed. The run itself is not written.
 */
@Command(
        name = "whatif",
        description =
                "Changes one thing in a past run and shows how it would have gone: the trajectory, the result and the effects that differ. Runs nothing; the run is not written.")
final class WhatIfCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "The run directory, or a run id with --store.")
    String run;

    @Option(
            names = "--store",
            description = "The trigger store whose runs/ folder holds the run id.")
    File store;

    @Option(
            names = "--at",
            required = true,
            description =
                    "The moment to go back to: a step id, a graph node (n3) or a source line.")
    String at;

    @Option(names = "--set", description = "A variable at that step: name=value (repeatable).")
    Map<String, String> sets = new LinkedHashMap<>();

    @Option(
            names = "--answer",
            description =
                    "What an agent or classifier says there: Name=text, or Name={json} (a classifier takes a label for each question) (repeatable).")
    Map<String, String> answers = new LinkedHashMap<>();

    @Option(names = "--tool", description = "What a tool returns: Name=text (repeatable).")
    Map<String, String> tools = new LinkedHashMap<>();

    @Option(
            names = "--fail",
            description =
                    "A failure to inject: Target=category[:times], for example Replier=rate_limit:2 (repeatable).")
    Map<String, String> fails = new LinkedHashMap<>();

    @Option(names = "--human", description = "What a person answers there (repeatable, in order).")
    List<String> humans = new ArrayList<>();

    @Option(
            names = "--models",
            description =
                    "recorded (default: reuse a recorded answer where its question is unchanged) or scripted (only the answers given).",
            defaultValue = "recorded")
    String models;

    @Option(
            names = "--effects",
            description = "simulate (the only mode here: nothing is ever sent or written).",
            defaultValue = "simulate")
    String effects;

    @Option(names = "--until", description = "Stop before this step.")
    String until;

    @Option(
            names = "--save",
            description = "Write the candidate as a new run directory, to open with weave why.")
    File save;

    @Option(names = "--format", description = "text (default), md or json.", defaultValue = "text")
    String format;

    @Override
    public Integer call() {
        return whatif(this, WeaveEnv.system());
    }

    static int whatif(WhatIfCommand c, WeaveEnv env) {
        if (!List.of("text", "md", "json").contains(c.format)) {
            env.err().println("Error: --format takes text, md or json.");
            return 2;
        }
        if (!c.effects.equals("simulate")) {
            env.err()
                    .println(
                            "Error: weave whatif never performs effects: nothing is sent or written. --effects takes simulate.");
            return 2;
        }
        if (c.models.equals("live")) {
            env.err()
                    .println(
                            "Error: live models need a spend cap and a candidate script; they come later. Use --models recorded or scripted.");
            return 2;
        }
        Path dir = c.store != null ? Runs.scheduledRunDir(c.store.toPath(), c.run) : Path.of(c.run);
        dir = dir.toAbsolutePath().normalize();
        RunSpec spec;
        try {
            spec = RunSpec.read(dir);
        } catch (RuntimeException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
        Path script = Path.of(spec.script());
        if (!Files.isRegularFile(script)) {
            env.err().println("Error: the script " + script + " is not there.");
            return 2;
        }
        String recorded = RunSpec.scriptHashOf(dir);
        if (recorded != null && !recorded.equals(ScriptHash.of(script))) {
            env.err()
                    .println(
                            "Error: "
                                    + script
                                    + " has changed since this run started, so going back in the run would not be going back in this script.");
            return 2;
        }
        List<WhatIf.Change> changes = new ArrayList<>();
        try {
            c.sets.forEach((k, v) -> changes.add(new WhatIf.Change("set", k, v, 1)));
            c.answers.forEach((k, v) -> changes.add(new WhatIf.Change("answer", k, v, 1)));
            c.tools.forEach((k, v) -> changes.add(new WhatIf.Change("tool", k, v, 1)));
            c.humans.forEach(v -> changes.add(new WhatIf.Change("human", "", v, 1)));
            for (Map.Entry<String, String> f : c.fails.entrySet()) {
                String[] parts = f.getValue().split(":", 2);
                int times = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                changes.add(new WhatIf.Change("fail", f.getKey(), parts[0], times));
            }
        } catch (NumberFormatException e) {
            env.err().println("Error: --fail takes Target=category or Target=category:times.");
            return 2;
        }
        Why.Selector selector =
                c.at.matches("\\d+")
                        ? new Why.Selector(null, null, Integer.parseInt(c.at))
                        : c.at.matches("n\\d+")
                                ? new Why.Selector(null, c.at, null)
                                : new Why.Selector(c.at, null, null);
        WhatIf.Outcome outcome;
        FileRunJournal journal = new FileRunJournal(dir.resolve(TravelCommands.JOURNAL));
        try {
            outcome =
                    WhatIf.run(
                            new WhatIf.Request(
                                    script,
                                    spec.workflow(),
                                    spec.inputs(),
                                    journal,
                                    selector,
                                    changes,
                                    c.models,
                                    c.until,
                                    Set.of()));
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            env.err().println("Error: " + (e.getMessage() == null ? e : e.getMessage()));
            return 2;
        }
        switch (c.format) {
            case "json" -> env.out().print(WhatIfRenderer.json(outcome));
            case "md" -> env.out().print(WhatIfRenderer.markdown(outcome));
            default -> env.out().print(WhatIfRenderer.text(outcome));
        }
        if (c.save != null) {
            Path target = c.save.toPath().toAbsolutePath().normalize();
            if (Files.exists(target.resolve(RunSpec.FILE))) {
                env.err()
                        .println(
                                "Error: "
                                        + target
                                        + " already holds a run; --save writes a new one.");
                return 2;
            }
            RunSpec saved =
                    new RunSpec(
                            spec.script(),
                            spec.loot(),
                            spec.workflow(),
                            spec.inputs(),
                            spec.maxTokens(),
                            spec.maxCalls(),
                            spec.maxCost(),
                            spec.prices(),
                            spec.store(),
                            spec.lenient(),
                            spec.trace(),
                            true,
                            Map.of(
                                    "run",
                                    dir.toString(),
                                    "at",
                                    outcome.at(),
                                    "whatif",
                                    changes.stream().map(WhatIf.Change::describe).toList()),
                            null,
                            spec.maxRewinds(),
                            spec.promptsDir(),
                            spec.promptPins());
            saved.write(target);
            FileRunJournal into = new FileRunJournal(target.resolve(TravelCommands.JOURNAL));
            outcome.candidate().journal().forEach(into::put);
            env.err().println("Saved the candidate as " + target + " (weave why " + target + ").");
        }
        return 0;
    }
}
