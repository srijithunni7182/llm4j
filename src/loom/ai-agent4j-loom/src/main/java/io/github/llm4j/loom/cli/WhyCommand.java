package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.explain.Why;
import io.github.llm4j.loom.explain.WhyRenderer;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.travel.ScriptHash;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave why}: why a run went where it did. The run is replayed from its journal with nothing
 * that has an effect (no model, tool, task, person or network), so the answer is exact, the same
 * every time, and needs none of the run's keys. It writes nothing, not even to the run's directory.
 */
@Command(
        name = "why",
        description =
                "Explains a run: each choice it made, over which values, where they came from, and the way it did not go. Reads the run's journal; runs nothing.")
final class WhyCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            description =
                    "The run directory (as given to run --journal), or a run id with --store.")
    String run;

    @Parameters(
            index = "1",
            arity = "0..1",
            description =
                    "A statement to ask about: a step id (Main/s2), a graph node (n7) or a line number.")
    String what;

    @Option(
            names = "--store",
            description = "The trigger store whose runs/ folder holds the run id.")
    File store;

    @Option(names = "--format", description = "text (default), md or json.", defaultValue = "text")
    String format;

    @Option(
            names = "--all",
            description = "Every step, not only the choices and the steps that did something.")
    boolean all;

    @Option(names = "--step", description = "Ask about this step id.")
    String step;

    @Option(names = "--node", description = "Ask about this graph node.")
    String node;

    @Option(names = "--line", description = "Ask about the statement on this line.")
    Integer line;

    @Option(names = "--since", description = "Only what happened after this step.")
    String since;

    @Option(
            names = "--script",
            description =
                    "Explain against this script instead of the one the run used (needed when the script has changed since).")
    File script;

    @Override
    public Integer call() {
        return why(this, WeaveEnv.system());
    }

    static int why(WhyCommand c, WeaveEnv env) {
        if (!java.util.List.of("text", "md", "json").contains(c.format)) {
            env.err().println("Error: --format takes text, md or json.");
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
        Path scriptFile = c.script != null ? c.script.toPath() : Path.of(spec.script());
        if (!Files.isRegularFile(scriptFile)) {
            env.err()
                    .println(
                            "Error: the script "
                                    + scriptFile
                                    + " is not there. Give the one the run used with --script.");
            return 2;
        }
        java.util.List<String> notes = new java.util.ArrayList<>();
        String recorded = RunSpec.scriptHashOf(dir);
        if (c.script == null) {
            if (recorded != null && !recorded.equals(ScriptHash.of(scriptFile))) {
                env.err()
                        .println(
                                "Error: "
                                        + scriptFile
                                        + " has changed since this run started, so an explanation from it would not be the run's. Give the script the run used with --script old.loom.");
                return 2;
            }
            if (recorded == null)
                notes.add(
                        "This run did not record which script it used, so the script could not be checked against it; where they disagree the explanation says so.");
        } else {
            notes.add(
                    "Explained against "
                            + scriptFile
                            + ", not necessarily the script the run used.");
        }
        Why.Selector selector = selector(c);
        Why.Report report;
        try {
            report =
                    Why.explain(
                            scriptFile,
                            spec.workflow(),
                            spec.inputs(),
                            new FileRunJournal(dir.resolve(TravelCommands.JOURNAL)),
                            new Why.Request(selector, c.all, c.since),
                            java.util.Set.of());
        } catch (Exception e) {
            env.err().println("Error: " + (e.getMessage() == null ? e : e.getMessage()));
            return 2;
        }
        if (!notes.isEmpty()) {
            java.util.List<String> all = new java.util.ArrayList<>(report.notes());
            all.addAll(0, notes);
            report =
                    new Why.Report(
                            report.version(),
                            report.workflow(),
                            report.script(),
                            report.status(),
                            report.reason(),
                            report.stoppedAt(),
                            report.entries(),
                            all,
                            report.answer());
        }
        switch (c.format) {
            case "json" -> env.out().print(WhyRenderer.json(report));
            case "md" -> env.out().print(WhyRenderer.markdown(report));
            default -> env.out().print(WhyRenderer.text(report));
        }
        return 0;
    }

    private static Why.Selector selector(WhyCommand c) {
        String s = c.step;
        String n = c.node;
        Integer l = c.line;
        if (c.what != null) {
            if (c.what.matches("\\d+")) l = Integer.parseInt(c.what);
            else if (c.what.matches("n\\d+")) n = c.what;
            else s = c.what;
        }
        return new Why.Selector(s, n, l);
    }
}
