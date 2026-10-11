package io.github.llm4j.loom.ladder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A case as the ledger holds it, folded from its records: what the agent saw, what it proposed (a choice, or an artifact), what the person did
 * with it (accepted, edited, rejected), and what happened afterwards.
 *
 * @param level      the rung the case ran at
 * @param how        why that rung (the earned one, an audit sample, an escalation, a freeze…)
 * @param superseded a later generation of the run decided this case again; it counts in nothing
 * @param shown      whether the person saw the proposal when responding
 * @param responder  the person who responded, or {@code agent} when the proposal took effect on its own
 */
public record LCase(String id, String decision, String scope, Instant at, String level, String how, String identity, int epoch, int generation,
                    boolean superseded, Map<String, String> fields, String servedModel,
                    String proposalChoice, String proposalValue, String reasoning, Double confidence, boolean malformed, Instant proposedAt, List<String> flags,
                    boolean responded, String responseKind, String responseChoice, String responseFinal, String responder, boolean shown,
                    Long reviewMillis, Instant respondedAt, List<Outcome> outcomes) {

    /** A later fact about a case: {@code reverted}, or {@code views_7d} with a number. */
    public record Outcome(String name, Object value, Instant at) { }

    /** A person responded without seeing the proposal: the only kind of case that is independent evidence. */
    public boolean blind() {
        return responded && !shown && responder != null && !"agent".equals(responder);
    }

    /** The name of the most recent outcome, or null when none has arrived. */
    public String latestOutcome() {
        return outcomes.isEmpty() ? null : outcomes.get(outcomes.size() - 1).name();
    }
}
