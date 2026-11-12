package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.SuperviseDef;
import io.github.llm4j.loom.ast.SuperviseStmt;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The counted rules of one {@code supervise} block, decided from numbers alone (phase 33, D23 and
 * D30): no clock, no thread and no model, so every rule can be tested exactly.
 *
 * <p>A rule fires when its condition <em>becomes</em> true and again only after it went false and
 * true; {@code once} limits it to one firing for good. Rules are taken in the order written. A
 * {@code cancel} or {@code wrap_up} ends the evaluation of the tick. At most one steer is delivered
 * a tick: a steer in a supervise block reaches the child's steerable agents, so a second one in the
 * same tick is recorded as skipped.
 */
public final class SuperviseEvaluator {

    /** The counters of a child at one look (D23); {@code elapsed} and {@code idle} in milliseconds. */
    public record Facts(long tokens, long calls, BigDecimal cost, long elapsedMillis, long idleMillis) {}

    /**
     * One action a tick decided on, or skipped. {@code rule} is the rule's index in the block;
     * {@code outcome} is {@code "fired"} or why it was not.
     */
    public record Firing(int rule, SuperviseDef.Action action, String outcome, boolean endsEvaluation) {
        /** True when the action is to be carried out. */
        public boolean delivered() {
            return "fired".equals(outcome);
        }
    }

    private static final String ALREADY_STEERED = "skipped: already steered this tick";

    private final List<SuperviseStmt.Counted> rules;
    private final boolean[] wasTrue;
    private final boolean[] spent;

    /**
     * @param block the supervise block
     * @param firedBefore the indexes of counted rules that fired before a restart: they do not fire
     *     again while they stay true, and a {@code once} rule never does
     */
    public SuperviseEvaluator(SuperviseStmt block, Set<Integer> firedBefore) {
        this.rules = List.copyOf(block.getCounted());
        this.wasTrue = new boolean[rules.size()];
        this.spent = new boolean[rules.size()];
        for (int i : firedBefore) {
            if (i < 0 || i >= rules.size()) continue;
            wasTrue[i] = true;
            if (rules.get(i).once()) spent[i] = true;
        }
    }

    /** What to do now, given how the child stands. */
    public List<Firing> evaluate(Facts facts) {
        List<Firing> out = new ArrayList<>();
        boolean steered = false;
        boolean ended = false;
        for (int i = 0; i < rules.size(); i++) {
            SuperviseStmt.Counted rule = rules.get(i);
            boolean holds = holds(rule, facts);
            boolean becameTrue = holds && !wasTrue[i];
            wasTrue[i] = holds;
            if (ended || !becameTrue || spent[i]) continue;
            if (rule.once()) spent[i] = true;
            for (SuperviseDef.Action action : rule.actions()) {
                if (action.kind() == SuperviseDef.Do.STEER) {
                    if (steered) {
                        out.add(new Firing(i, action, ALREADY_STEERED, false));
                        continue;
                    }
                    steered = true;
                }
                boolean ends = action.kind() != SuperviseDef.Do.STEER;
                out.add(new Firing(i, action, "fired", ends));
                if (ends) {
                    ended = true;
                    break;
                }
            }
        }
        return out;
    }

    private static boolean holds(SuperviseStmt.Counted rule, Facts facts) {
        BigDecimal actual =
                switch (rule.counter()) {
                    case TOKENS -> BigDecimal.valueOf(facts.tokens());
                    case CALLS -> BigDecimal.valueOf(facts.calls());
                    case COST -> facts.cost();
                    case ELAPSED -> BigDecimal.valueOf(facts.elapsedMillis());
                    case IDLE -> BigDecimal.valueOf(facts.idleMillis());
                };
        BigDecimal limit = rule.money() != null ? rule.money() : BigDecimal.valueOf(rule.number());
        return rule.comparison().holds(actual, limit);
    }
}
