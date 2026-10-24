package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.demo.Narrator;
import io.github.llm4j.loom.demo.Scenarios;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.travel.ObserveReplay;
import io.github.llm4j.loom.wtest.CaseProbe;
import io.github.llm4j.loom.wtest.TestFile;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave try}: a real workflow, run by the real interpreter on pre-written (demo) model
 * replies, narrated step by step. No key, no network, nothing left behind unless {@code --keep}.
 */
@Command(
        name = "try",
        description =
                "Runs a bundled workflow on demo models (pre-written replies; no key, no network, nothing written) and explains each step. --list shows the scenarios; --keep DIR leaves the project to edit.")
final class TryCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "scenario",
            description = "Which scenario (default triage).")
    String scenario = "triage";

    @Option(names = "--list", description = "List the scenarios and what each shows.")
    boolean list;

    @Option(names = "--step", description = "Pause between steps; press Enter to go on.")
    boolean step;

    @Option(names = "--fast", description = "No pauses, for scripts.")
    boolean fast;

    @Option(
            names = "--keep",
            paramLabel = "dir",
            description = "Write the scenario's project into this folder, to open and edit.")
    File keep;

    @Option(names = "--no-color", description = "Plain text (also NO_COLOR).")
    boolean noColor;

    /** Held so the logger (and its level) is not collected. */
    private static final java.util.logging.Logger QUIET =
            java.util.logging.Logger.getLogger("io.github.llm4j.loom.execution");

    static final String SCRIPTED =
            "Demo models: every reply is pre-written, so this works offline, instantly, for free. Real models vary.";

    @Override
    public Integer call() {
        return run(this, WeaveEnv.system(), System.in);
    }

    static int run(TryCommand c, WeaveEnv env, java.io.InputStream in) {
        Path temp = null;
        // the runs below load the script several times; what the loader warns about is in the
        // review line, and a log line in the middle of a tour is noise
        QUIET.setLevel(java.util.logging.Level.OFF);
        try {
            if (c.list) {
                for (Scenarios.Scenario s : Scenarios.list())
                    env.out()
                            .println(
                                    String.format(
                                            "%-10s %s%n           shows: %s",
                                            s.name(), s.description(), s.shows()));
                return 0;
            }
            Scenarios.Scenario s = Scenarios.find(c.scenario);
            if (s == null) {
                env.err()
                        .println(
                                "Error: there is no scenario \""
                                        + c.scenario
                                        + "\". The scenarios are: "
                                        + Scenarios.list().stream()
                                                .map(Scenarios.Scenario::name)
                                                .toList()
                                        + ". Try: weave try --list");
                return 2;
            }
            if (c.keep != null) {
                Path keep = c.keep.toPath();
                if (Files.exists(keep.resolve("main.loom"))) {
                    env.err()
                            .println(
                                    "Error: "
                                            + keep
                                            + " already has a main.loom; choose an empty folder.");
                    return 2;
                }
                Scenarios.write(s, keep);
                env.out()
                        .println(
                                "Wrote the "
                                        + s.name()
                                        + " project to "
                                        + keep
                                        + ". Next: cd "
                                        + keep
                                        + " && weave test");
                return 0;
            }
            temp = Files.createTempDirectory("weave-try-");
            Scenarios.write(s, temp);
            Path script = temp.resolve("main.loom");
            TestFile tests = TestFile.read(temp.resolve("tests/main.test.yaml"));
            TestFile.Case demo = tests.cases().get(0);
            env.out().println("weave try " + s.name() + ": " + s.description() + ".");
            env.out().println(SCRIPTED);
            env.out().println();
            var probe = CaseProbe.run(script, tests.workflow(), demo.body());
            if (!probe.problems().isEmpty() || !probe.failures().isEmpty()) {
                env.err()
                        .println(
                                "Error: the demo does not run as written: "
                                        + (probe.problems().isEmpty()
                                                ? probe.failures()
                                                : probe.problems()));
                return 1;
            }
            RunJournal journal = RunJournal.inMemory();
            probe.journal().forEach(journal::put);
            Map<String, String> inputs = new LinkedHashMap<>();
            demo.body()
                    .path("input")
                    .fields()
                    .forEachRemaining(e -> inputs.put(e.getKey(), e.getValue().asText()));
            ObserveReplay.Result replay =
                    ObserveReplay.run(
                            new ObserveReplay.Options(script, tests.workflow(), inputs, Set.of()),
                            journal);
            inputs.forEach((k, v) -> env.out().println("input  " + k + " = \"" + v + "\""));
            env.out().println();
            boolean pace = !c.fast && !c.step && System.console() != null;
            int n = 0;
            for (Narrator.Line line : Narrator.narrate(replay)) {
                env.out().println(++n + ". " + line.text());
                if (c.step) {
                    env.out().print("   (Enter for the next step) ");
                    env.out().flush();
                    in.read();
                } else if (pace) Thread.sleep(400);
            }
            env.out().println();
            env.out().println("path   " + String.join("  →  ", probe.route()));
            env.out().println("result " + clip(String.valueOf(probe.result())));
            env.out()
                    .println(
                            "status "
                                    + probe.status()
                                    + " · this demo cost $0 (a real run would spend tokens; weave review estimates it)");
            DeployCommand.Review review = DeployCommand.review(script.toFile(), env);
            env.out()
                    .println(
                            "review "
                                    + (review.summary() == null
                                            ? ""
                                            : review.summary().replace("**", "").replace("`", "")));
            env.out().println();
            env.out().println("Next:");
            env.out()
                    .println(
                            "  weave try "
                                    + s.name()
                                    + " --keep ./demo     the project, to open and edit");
            env.out()
                    .println(
                            "  weave test ./demo/main.loom                  its tests (they run on the same demo models)");
            env.out()
                    .println(
                            "  weave generate \"route support tickets\"       your own, from a sentence (needs a model key; weave doctor checks)");
            env.out().println();
            env.out().println(SCRIPTED);
            return 0;
        } catch (Exception e) {
            env.err().println("Error: " + (e.getMessage() == null ? e : e.getMessage()));
            return 2;
        } finally {
            if (temp != null) {
                try (Stream<Path> walk = Files.walk(temp)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (Exception ignored) {
                    // a temp folder that cannot be removed is the system's to clean
                }
            }
        }
    }

    private static String clip(String s) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }
}
