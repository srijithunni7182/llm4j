package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.eval.EvictionProbe;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code weave probe}: checks of a mechanism that {@code weave test} cannot script. {@code eviction}
 * runs the real context manager on a small window and says what it removed. No model is called.
 */
@Command(
        name = "probe",
        mixinStandardHelpOptions = true,
        description = "Probes of a mechanism with no model: how the context manager evicts on a small window.",
        subcommands = {ProbeCommand.Eviction.class})
final class ProbeCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(
            name = "eviction",
            mixinStandardHelpOptions = true,
            description =
                    "Plants a fact early in a long simulated run on a small context window, lets the real context manager compact as the window fills, and checks that the oldest material went first, that the task stayed, and that the model was told something was removed (so no conclusion silently rests on what is gone). Exit 0 it behaved, 1 it did not or the probe tested no eviction, 2 a problem with the options. It calls no model, so it proves the mechanism and not what an agent does with a gap: pair it with a monotonicity case from weave metamorphic.")
    static final class Eviction implements Callable<Integer> {
        @Option(names = "--strategy", defaultValue = "drop_oldest", paramLabel = "<name>", description = "The compaction strategy, one that calls no model, such as drop_oldest or trim_tool_results (default: ${DEFAULT-VALUE}).")
        String strategy;

        @Option(names = "--window", defaultValue = "4000", description = "The simulated model window in tokens (default: ${DEFAULT-VALUE}).")
        int window;

        @Option(names = "--turns", defaultValue = "30", description = "How many turns the run has (default: ${DEFAULT-VALUE}).")
        int turns;

        @Option(names = "--fact-at", defaultValue = "2", description = "The turn that carries the planted fact (default: ${DEFAULT-VALUE}).")
        int factAt;

        @Option(names = "--fact", defaultValue = "the vault code is 4417", description = "The planted fact (default: ${DEFAULT-VALUE}).")
        String fact;

        @Option(names = "--turn-chars", defaultValue = "800", description = "How long each turn's output is (default: ${DEFAULT-VALUE}).")
        int turnChars;

        @Option(names = "--keep-recent", defaultValue = "2", description = "How many of the latest messages compaction must leave alone (default: ${DEFAULT-VALUE}).")
        int keepRecent;

        @Override
        public Integer call() {
            return probe(this, WeaveEnv.system());
        }

        static int probe(Eviction c, WeaveEnv env) {
            EvictionProbe.Spec spec;
            EvictionProbe.Result result;
            try {
                spec = new EvictionProbe.Spec(c.strategy, c.window, c.fact, c.factAt, c.turns, c.turnChars, c.keepRecent);
                result = EvictionProbe.run(spec);
            } catch (IllegalArgumentException e) {
                env.err().println("Error: " + e.getMessage() + ".");
                return 2;
            }
            EvictionProbe.lines(spec, result).forEach(env.out()::println);
            return result.ok() ? 0 : 1;
        }
    }
}
