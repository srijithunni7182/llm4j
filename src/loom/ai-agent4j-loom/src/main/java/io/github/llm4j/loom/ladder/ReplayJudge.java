package io.github.llm4j.loom.ladder;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.autonomy.expr.V;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What a changed agent would have earned: its proposals for the old agent's past cases, set against
 * what the people decided then, judged by the ladder's own conditions as though those were the
 * record it had made. Pure: it reads the cases it is given and writes nothing.
 */
public final class ReplayJudge {

    /** The rung the replay supports and why it stops there. */
    public record Verdict(String rung, String why) {}

    private ReplayJudge() {}

    /**
     * @param script the script whose ladder judges
     * @param decision the decision bound to the ladder
     * @param original every case of the decision as the ledger holds it
     * @param candidate for each replayed case id, what the changed agent proposed in its place (a
     *     choice)
     */
    public static Verdict judge(
            LoomScript script,
            DecisionDef decision,
            List<LCase> original,
            Map<String, String> candidate) {
        LadderPolicy policy = LadderPolicy.of(script, decision);
        List<LCase> replayed = new ArrayList<>();
        Instant last = Instant.EPOCH;
        for (LCase c : original) {
            String choice = candidate.get(c.id());
            if (choice == null || c.superseded() || !c.responded()) continue;
            replayed.add(
                    new LCase(
                            c.id(),
                            c.decision(),
                            "replay",
                            c.at(),
                            c.level(),
                            c.how(),
                            c.identity(),
                            1,
                            c.generation(),
                            false,
                            c.fields(),
                            c.servedModel(),
                            choice,
                            c.proposalValue(),
                            c.reasoning(),
                            c.confidence(),
                            false,
                            c.proposedAt(),
                            c.flags(),
                            true,
                            c.responseKind(),
                            c.responseChoice(),
                            c.responseFinal(),
                            c.responder(),
                            c.shown(),
                            c.reviewMillis(),
                            c.respondedAt(),
                            c.outcomes()));
            if (c.at().isAfter(last)) last = c.at();
        }
        // the history is judged as of the day of its last case: the record was complete then, and
        // "the last 30 days" means those days
        V.Cs history = HistoryBuilder.history(replayed, "replay", 1, decision.getRememberTypes());
        long now = last.toEpochMilli();
        int rung = 0;
        while (rung + 1 <= policy.ceiling()
                && rung + 1 < policy.rungs().size()
                && policy.climbs(rung + 1, history, now)
                && policy.slip(rung + 1, history, now).isEmpty()) rung++;
        while (rung > 0 && policy.slip(rung, history, now).isPresent())
            rung = policy.slip(rung, history, now).get();
        int next = rung + 1;
        String why;
        if (policy.ceiling() > rung && next < policy.rungs().size()) {
            String unmet =
                    policy.explainClimb(next, history, now).stream()
                            .filter(t -> t.met().map(m -> !m).orElse(true))
                            .map(
                                    t ->
                                            t.text()
                                                    + " ["
                                                    + t.actual()
                                                    + (t.needed().isEmpty()
                                                            ? ""
                                                            : " vs " + t.needed())
                                                    + "]")
                            .collect(Collectors.joining("; "));
            why =
                    "not enough for "
                            + policy.nameOf(next)
                            + ": "
                            + unmet
                            + " (judged on "
                            + replayed.size()
                            + " replayed cases)";
        } else if (rung == policy.rungs().size() - 1) {
            why =
                    "every condition up to "
                            + policy.nameOf(rung)
                            + " is met by "
                            + replayed.size()
                            + " replayed cases";
        } else {
            why =
                    "every condition up to "
                            + policy.nameOf(rung)
                            + " is met by "
                            + replayed.size()
                            + " replayed cases (the ceiling, "
                            + policy.nameOf(policy.ceiling())
                            + ", still applies)";
        }
        return new Verdict(policy.nameOf(rung), why);
    }
}
