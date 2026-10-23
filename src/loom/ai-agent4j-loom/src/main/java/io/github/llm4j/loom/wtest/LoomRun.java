package io.github.llm4j.loom.wtest;

import io.github.llm4j.loom.graph.GraphResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** What a workflow did in a test run: how it ended, what it returned, and what it went through. */
public final class LoomRun {

    private final CaseRunner.Outcome outcome;
    private final GraphResult graph;
    private final String workflow;

    LoomRun(CaseRunner.Outcome outcome, GraphResult graph, String workflow) {
        this.outcome = outcome;
        this.graph = graph;
        this.workflow = workflow;
    }

    /** completed, failed, suspended, stopped or budget. */
    public String status() {
        return outcome.status();
    }

    /** Why the run failed, or null. */
    public String error() {
        return outcome.error();
    }

    /** What the workflow returned (a text, or a map for a structured result), or null. */
    public Object result() {
        return outcome.result();
    }

    /** A variable at the end of the run, by dotted path such as {@code intent.kind}. */
    public Object variable(String path) {
        return Values.path(outcome.variables(), path);
    }

    /** The steps that ran, as the labels {@code weave graph} gives them. */
    public List<String> route() {
        return outcome.recorder().route();
    }

    /** Calls to an agent, classifier, tool or task, failed ones included. */
    public int calls(String name) {
        Map<String, Integer> all = new java.util.LinkedHashMap<>();
        all.putAll(outcome.recorder().taskCalls());
        all.putAll(outcome.recorder().toolCalls());
        all.putAll(outcome.recorder().callCounts());
        return all.getOrDefault(name, 0);
    }

    /** What acting tools would have done. */
    public List<String> effects() {
        return outcome.recorder().effects().stream()
                .map(
                        e ->
                                e.tool()
                                        + " ("
                                        + e.kind()
                                        + (e.target().isEmpty() ? "" : " to " + e.target())
                                        + ")")
                .toList();
    }

    /** The questions put to a person, in order. */
    public List<String> asked() {
        return outcome.recorder().asked().stream().map(m -> m.get("prompt")).toList();
    }

    /** How long the run waited in virtual time. */
    public Duration elapsedVirtual() {
        return outcome.elapsedVirtual();
    }

    /** The tokens the scripted calls used. */
    public long tokens() {
        return outcome.recorder().tokens();
    }

    /** The handlers that took over a failed step. */
    public List<String> handlers() {
        return outcome.recorder().handlers();
    }

    /** The graph nodes the run reached, as {@code workflow/nodeId}. */
    public java.util.Set<String> reached() {
        Coverage c = new Coverage(graph);
        c.add(outcome.recorder().steps());
        return c.reached();
    }

    /** The coverage of the script by this run alone. */
    public Coverage coverage() {
        Coverage c = new Coverage(graph);
        c.add(outcome.recorder().steps());
        return c;
    }

    /** The unscripted calls that were refused, which fail a case. */
    public List<String> unscripted() {
        return outcome.recorder().violations();
    }

    String workflow() {
        return workflow;
    }

    CaseRunner.Outcome outcome() {
        return outcome;
    }

    GraphResult graph() {
        return graph;
    }
}
