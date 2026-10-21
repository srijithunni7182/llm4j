package io.github.llm4j.loom.cli;

import picocli.CommandLine.Option;

/**
 * {@code --steer-via console}: the person who started the run may steer, wrap up, cancel, pause and
 * resume its agents from this console.
 */
final class SteerMixin {

    @Option(
            names = "--steer-via",
            paramLabel = "console",
            description =
                    "Where a person may steer this run's agents while they work (console: type commands here). Agents must be declared steerable.")
    private String steerVia;

    /** Starts what was asked for; false (after saying why) when the value is not one we know. */
    boolean start(java.io.PrintStream out, java.io.PrintStream err) {
        if (steerVia == null) return true;
        if (!"console".equals(steerVia)) {
            err.println(
                    "Error: --steer-via "
                            + steerVia
                            + " is not known. Use console. A chat reaches a run through `weave daemon` with a channel (--ask-via).");
            return false;
        }
        ConsoleInput.start(System.in, out);
        return true;
    }
}
