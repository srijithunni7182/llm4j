package io.github.llm4j.loom.eval;

import java.math.BigDecimal;
import java.util.List;

/**
 * The outcome of one scenario.
 *
 * @param target the agent or workflow it was run against
 * @param kind {@code agent} or {@code workflow}
 * @param file the dataset file it came from
 * @param cost what the run cost when prices are known, else null
 * @param error what stopped the run before its checks could be made, else null
 * @param input what the scenario asked (null when it had no input)
 * @param output what was judged: the agent's answer or the workflow's output (null when the run
 *     never got that far)
 * @param tags the scenario's tags, such as {@code agent:Writer} or {@code split:validation}
 * @param dimensions the quality dimensions the scenario names
 * @param agents what each agent that took part did, from the trace
 */
public record ScenarioResult(
        String target,
        String kind,
        String file,
        String id,
        String name,
        Status status,
        List<Check> checks,
        BigDecimal cost,
        String error,
        String input,
        String output,
        List<String> tags,
        List<String> dimensions,
        List<AgentFacts> agents) {

    /** A result with nothing but the verdicts, as before outputs and agents were kept. */
    public ScenarioResult(
            String target,
            String kind,
            String file,
            String id,
            String name,
            Status status,
            List<Check> checks,
            BigDecimal cost,
            String error) {
        this(target, kind, file, id, name, status, checks, cost, error, null, null, List.of(), List.of(), List.of());
    }

    /**
     * One thing that was checked: {@code answer contains}, {@code tools}, {@code rubric}, {@code
     * expect}.
     */
    public record Check(String kind, String text, Status status, String detail) {}

    public ScenarioResult {
        checks = List.copyOf(checks);
        tags = tags == null ? List.of() : List.copyOf(tags);
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        agents = agents == null ? List.of() : List.copyOf(agents);
    }

    /** A name to show: the id if it has one, else the name. */
    public String label() {
        return id != null ? id : name != null ? name : "(unnamed)";
    }
}
