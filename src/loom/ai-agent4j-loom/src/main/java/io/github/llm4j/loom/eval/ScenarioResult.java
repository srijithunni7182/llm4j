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
 */
public record ScenarioResult(String target, String kind, String file, String id, String name, Status status, List<Check> checks, BigDecimal cost, String error) {

    /** One thing that was checked: {@code answer contains}, {@code tools}, {@code rubric}, {@code expect}. */
    public record Check(String kind, String text, Status status, String detail) {}

    public ScenarioResult {
        checks = List.copyOf(checks);
    }

    /** A name to show: the id if it has one, else the name. */
    public String label() {
        return id != null ? id : name != null ? name : "(unnamed)";
    }
}
