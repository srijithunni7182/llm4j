package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.StopReport;

/**
 * Decides what to do with a step whose agent was stopped, from the reason and the agent's report. It is the default table of the design: what happens
 * when a script says nothing. A person who stops an agent may name the outcome; a retry they name is still refused when the agent may already have
 * changed something outside the run, because running it again could repeat that.
 */
final class StopInterpreter {

    /** The outcome and the reason for it, in words a person or an audit can read. */
    record Decision(StopOutcome outcome, String why) { }

    private StopInterpreter() { }

    static Decision decide(StopReport stop, StopOutcome named, boolean someoneToAsk) {
        return decide(stop, named, false, someoneToAsk);
    }

    /** As above; {@code declaredByScript} says the named outcome came from the script's {@code on_stopped} or {@code intervention}, not from a person. */
    static Decision decide(StopReport stop, StopOutcome named, boolean declaredByScript, boolean someoneToAsk) {
        Decision d = choose(stop, named, declaredByScript);
        if (d.outcome() == StopOutcome.ASK && !someoneToAsk) {
            return new Decision(StopOutcome.SUSPEND, d.why() + "; nobody is set up to ask, so the run is suspended until it is resumed");
        }
        return d;
    }

    private static Decision choose(StopReport stop, StopOutcome named, boolean declaredByScript) {
        boolean changed = stop.mayHaveChangedThings();
        String reason = stop.reason() == null ? "" : stop.reason().toLowerCase(java.util.Locale.ROOT);
        if (named != null) {
            if (named == StopOutcome.RETRY && changed) {
                return new Decision(StopOutcome.ASK, "a retry was asked for, but the agent may already have changed something outside the run");
            }
            return new Decision(named, declaredByScript ? "declared by the script" : "named by whoever stopped it");
        }
        if (stop.kind() == StopReport.Kind.WRAP_UP) {
            return reason.equals("safety") ? new Decision(StopOutcome.ASK, "a safety stop is never settled silently")
                    : new Decision(StopOutcome.USE_PARTIAL, "the wrap-up answer stands");
        }
        return switch (reason) {
            case "shutdown" -> new Decision(StopOutcome.SUSPEND, "the process is shutting down");
            case "sibling" -> new Decision(StopOutcome.DISCARD, "a sibling step decided the outcome");
            case "budget" -> new Decision(StopOutcome.USE_PARTIAL, "the budget ran out");
            case "timeout" -> changed ? new Decision(StopOutcome.USE_PARTIAL, "it timed out after changing something, so it is not run again")
                    : new Decision(StopOutcome.RETRY, "it timed out before changing anything");
            case "safety" -> new Decision(StopOutcome.ASK, "a safety stop is never settled silently");
            default -> new Decision(StopOutcome.ASK, "stopped by " + (stop.origin() == null ? "someone" : stop.origin()) + (reason.isEmpty() ? "" : " (" + stop.reason() + ")"));
        };
    }
}
