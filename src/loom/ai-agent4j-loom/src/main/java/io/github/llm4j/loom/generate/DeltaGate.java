package io.github.llm4j.loom.generate;

import io.github.llm4j.loom.review.model.Severity;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * G2 for an edit: not "is the review clean" but "is the review worse than it was". A finding that
 * was not open before and is HIGH now fails it, and so does a worst-case cost that has grown by
 * more than the allowed factor (or has become unbounded). What changed is kept for the report
 * either way.
 */
public final class DeltaGate implements Gate {

    /** An open finding, by the fingerprint that stays the same when lines move. */
    public record Found(
            String fingerprint, String rule, Severity severity, int line, String title) {}

    /** The tokens a workflow takes in a typical run and in the worst case the script allows. */
    public record WorkflowTokens(long typical, long worst, boolean unbounded) {}

    /** What a review says about a script, reduced to what the comparison needs. */
    public record Snapshot(List<Found> open, Map<String, WorkflowTokens> cost) {
        public Snapshot {
            open = List.copyOf(open);
            cost = Map.copyOf(cost);
        }
    }

    /** Reviews a script (no fixes, nothing written). */
    public interface Reviewer {
        Snapshot snapshot(Path script) throws Exception;
    }

    /** What changed between the two reviews. */
    public record Delta(
            List<Found> added,
            List<Found> removed,
            Map<String, WorkflowTokens> before,
            Map<String, WorkflowTokens> after) {}

    private final Snapshot baseline;
    private final Reviewer reviewer;
    private final double factor;
    private volatile Delta last;

    /**
     * @param factor the most the worst-case tokens of a workflow may grow, as a multiple (1.5 =
     *     half as much again)
     */
    public DeltaGate(Snapshot baseline, Reviewer reviewer, double factor) {
        this.baseline = baseline;
        this.reviewer = reviewer;
        this.factor = factor;
    }

    @Override
    public String id() {
        return "G2";
    }

    /** What the last run found, for the report. */
    public Delta last() {
        return last;
    }

    @Override
    public GateResult run(Workspace ws, Plan plan) {
        try {
            Snapshot now = reviewer.snapshot(ws.script());
            Set<String> before = new HashSet<>();
            baseline.open().forEach(f -> before.add(f.fingerprint()));
            Set<String> after = new HashSet<>();
            now.open().forEach(f -> after.add(f.fingerprint()));
            List<Found> added =
                    now.open().stream().filter(f -> !before.contains(f.fingerprint())).toList();
            List<Found> removed =
                    baseline.open().stream().filter(f -> !after.contains(f.fingerprint())).toList();
            last =
                    new Delta(
                            added,
                            removed,
                            new TreeMap<>(baseline.cost()),
                            new TreeMap<>(now.cost()));
            List<GateResult.Problem> out = new ArrayList<>();
            for (Found f : added)
                if (f.severity() == Severity.HIGH)
                    out.add(
                            new GateResult.Problem(
                                    "main.loom",
                                    f.line(),
                                    f.rule(),
                                    "This change adds a HIGH finding: " + f.title()));
            for (var e : now.cost().entrySet()) {
                WorkflowTokens was = baseline.cost().get(e.getKey());
                WorkflowTokens is = e.getValue();
                if (was == null) continue;
                if (is.unbounded() && !was.unbounded())
                    out.add(
                            new GateResult.Problem(
                                    "main.loom",
                                    0,
                                    "cost",
                                    "Workflow "
                                            + e.getKey()
                                            + " was bounded and now has no upper bound on what it can spend."));
                else if (!was.unbounded() && was.worst() > 0 && is.worst() > was.worst() * factor)
                    out.add(
                            new GateResult.Problem(
                                    "main.loom",
                                    0,
                                    "cost",
                                    "Workflow "
                                            + e.getKey()
                                            + " can now spend up to "
                                            + is.worst()
                                            + " tokens, from "
                                            + was.worst()
                                            + " (more than "
                                            + factor
                                            + " times as much)."));
            }
            return new GateResult("G2", "review delta", out);
        } catch (Exception e) {
            return new GateResult(
                    "G2",
                    "review delta",
                    List.of(
                            new GateResult.Problem(
                                    "main.loom",
                                    0,
                                    "",
                                    "The review could not run: " + e.getMessage())));
        }
    }
}
