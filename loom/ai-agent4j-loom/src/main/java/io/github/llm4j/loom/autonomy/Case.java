package io.github.llm4j.loom.autonomy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A case as it stands: the fold of its ledger records in order.
 *
 * @param locator    where the run journal that holds the case's inputs is (a run directory, or a JDBC run id)
 * @param step       the generation-free id of the decide step in that run
 * @param shown      whether the person saw the proposal when deciding (a case decided blind has {@code shown} false)
 * @param superseded a later generation of the run decided this case again; it counts in no statistic
 * @param outcomes   later facts about the case ({@code reversed}, {@code upheld}, …), in the order they were recorded
 */
public record Case(String id, String decision, String scope, String locator, String step, Instant at, Map<String, String> fields, Level level,
                   String identity, int epoch, int generation, boolean superseded, List<String> flags,
                   String proposal, String reasoning, Double confidence, boolean malformed,
                   String verdict, String decider, boolean shown, Instant decidedAt, Long millis, List<String> outcomes) {

    /** The case was decided by a person who did not see the proposal: the only kind of case that counts as evidence. */
    public boolean blindEvidence() {
        return !superseded && verdict != null && proposal != null && !shown && decider != null && !"agent".equals(decider);
    }

    public boolean decidedByAgent() {
        return "agent".equals(decider);
    }

    public boolean reversed() {
        return outcomes.contains("reversed");
    }
}
