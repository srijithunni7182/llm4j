package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * Assertions on a {@link LoomRun}, in the style of AssertJ and with the messages {@code weave test}
 * gives (expected, actual). Each method throws {@link AssertionError}; nothing here needs a test
 * library.
 */
public final class LoomRunAssert {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LoomRun run;

    private LoomRunAssert(LoomRun run) {
        this.run = run;
    }

    public static LoomRunAssert assertThat(LoomRun run) {
        return new LoomRunAssert(run);
    }

    private static AssertionError fail(String message) {
        return new AssertionError(message);
    }

    /** No call was left unscripted, and the run completed. */
    public LoomRunAssert completed() {
        return ended("completed");
    }

    /** The run ended this way: completed, failed, suspended, stopped or budget. */
    public LoomRunAssert ended(String status) {
        if (!run.unscripted().isEmpty())
            throw fail("unscripted: " + String.join("; ", run.unscripted()));
        if (!run.status().equals(status))
            throw fail(
                    "expected the run to end "
                            + status
                            + ", it ended "
                            + run.status()
                            + (run.error() != null ? ": " + run.error() : ""));
        return this;
    }

    /**
     * The result equals this text, or matches the matcher (such as {@code {contains: x}} as JSON).
     */
    public LoomRunAssert returned(Object expected) {
        JsonNode want = expected instanceof JsonNode n ? n : JSON.valueToTree(expected);
        String problem = Matchers.mismatch(want, run.result());
        if (problem != null) throw fail("result: " + problem);
        return this;
    }

    /** A field of the result equals this value. */
    public LoomRunAssert returned(String field, Object expected) {
        JsonNode want = JSON.createObjectNode().set(field, JSON.valueToTree(expected));
        String problem = Matchers.mismatch(want, run.result());
        if (problem != null) throw fail("result: " + problem);
        return this;
    }

    /** The agent, classifier, tool or task was called this many times. */
    public LoomRunAssert called(String name, int times) {
        if (run.calls(name) != times)
            throw fail("calls." + name + ": expected " + times + ", got " + run.calls(name));
        return this;
    }

    /** These steps ran, in this order (others may run between them). */
    public LoomRunAssert ran(String... steps) {
        List<String> expected = new ArrayList<>(List.of(steps));
        String problem = RouteDiff.mismatch(expected, run.route(), false);
        if (problem != null) throw fail("route: " + problem);
        return this;
    }

    /** No step matching this name ran. */
    public LoomRunAssert neverRan(String step) {
        for (String s : run.route())
            if (Labels.matches(step, s))
                throw fail("never: \"" + step + "\" must not run, but \"" + s + "\" did");
        return this;
    }

    /** The arm of the alt node ({@code then} or {@code else}) ran. */
    public LoomRunAssert tookBranch(String node, String arm) {
        Coverage c = run.coverage();
        var found = c.find(run.workflow(), node);
        if (found.isEmpty())
            throw fail("branches." + node + ": no such step (weave test --steps lists them)");
        for (Coverage.Branch b : c.branches())
            if (b.owner().equals(found.get().id()) && b.branch().equals(arm)) {
                if (!b.covered())
                    throw fail(
                            "branches."
                                    + node
                                    + ": expected the "
                                    + arm
                                    + " arm to run, it did not");
                return this;
            }
        throw fail("branches." + node + ": the " + arm + " arm has no statements to run");
    }

    /** Acting tools would have done nothing. */
    public LoomRunAssert noEffects() {
        if (!run.effects().isEmpty())
            throw fail(
                    "effects: expected none, but "
                            + run.effects().size()
                            + " would have happened: "
                            + run.effects());
        return this;
    }

    /** Nobody was asked anything. */
    public LoomRunAssert nobodyAsked() {
        if (!run.asked().isEmpty())
            throw fail(
                    "asked: expected nobody to be asked, but "
                            + run.asked().size()
                            + " question(s) were put to a person: "
                            + run.asked());
        return this;
    }

    /** The scripted calls used at most this many tokens. */
    public LoomRunAssert tokensBelow(long max) {
        if (run.tokens() > max)
            throw fail("tokens: expected at most " + max + ", got " + run.tokens());
        return this;
    }

    /** The run waited exactly this long in virtual time (such as {@code 6s}). */
    public LoomRunAssert waited(String duration) {
        java.time.Duration d = Durations.parse(duration);
        if (d == null) throw fail("'" + duration + "' is not a duration such as 30s, 5m or 2h");
        if (!d.equals(run.elapsedVirtual()))
            throw fail(
                    "elapsed_virtual: expected "
                            + duration
                            + ", the run waited "
                            + Durations.show(run.elapsedVirtual()));
        return this;
    }

    /** The handler of a failed step took over, such as {@code on_failure of delegate Replier}. */
    public LoomRunAssert handledBy(String handler) {
        if (!run.handlers().contains(handler))
            throw fail(
                    "ran: no handler " + handler + " took over (handlers: " + run.handlers() + ")");
        return this;
    }
}
