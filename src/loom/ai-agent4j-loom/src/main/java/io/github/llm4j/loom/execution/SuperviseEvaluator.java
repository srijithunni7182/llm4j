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
    public record Facts(
            long tokens,
            long calls,
            BigDecimal cost,
            long elapsedMillis,
            long idleMillis,
            Trajectory.Snapshot trajectory,
            java.util.Map<String, Long> unchangedMillis) {

        public Facts {
            unchangedMillis = java.util.Map.copyOf(unchangedMillis);
        }

        /** As a child that has declared no progress: nothing to say about how long a field has stood still. */
        public Facts(
                long tokens, long calls, BigDecimal cost, long elapsedMillis, long idleMillis,
                Trajectory.Snapshot trajectory) {
            this(tokens, calls, cost, elapsedMillis, idleMillis, trajectory, java.util.Map.of());
        }

        /** The counters alone, for a child whose calls are not in question. */
        public Facts(long tokens, long calls, BigDecimal cost, long elapsedMillis, long idleMillis) {
            this(tokens, calls, cost, elapsedMillis, idleMillis, Trajectory.Snapshot.EMPTY);
        }
    }

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

    private record Rule(java.util.function.Predicate<Facts> holds, boolean once, List<SuperviseDef.Action> actions) {}

    private final List<Rule> rules;
    private final boolean[] wasTrue;
    private final boolean[] spent;

    /**
     * @param block the supervise block
     * @param firedBefore the indexes of counted rules that fired before a restart: they do not fire
     *     again while they stay true, and a {@code once} rule never does
     */
    public SuperviseEvaluator(SuperviseStmt block, Set<Integer> firedBefore) {
        List<Rule> all = new ArrayList<>();
        for (SuperviseStmt.Counted c : block.getCounted())
            all.add(new Rule(f -> holds(c, f), c.once(), c.actions()));
        for (SuperviseStmt.CodeAssertion a : block.getCode())
            all.add(new Rule(f -> violated(a, f), a.once(), a.actions()));
        this.rules = List.copyOf(all);
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
            Rule rule = rules.get(i);
            boolean holds = rule.holds().test(facts);
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

    /** An assertion in code is violated when what it forbids has happened (D24). */
    private static boolean violated(SuperviseStmt.CodeAssertion a, Facts facts) {
        Trajectory.Snapshot t = facts.trajectory();
        return switch (a.kind()) {
            case NEVER_CALLS -> t.refusedOf(a.name()) > 0 || t.callsOf(a.name()) > 0;
            case CALLS_AT_MOST -> t.callsOf(a.name()) > a.limit();
            case CALLS_BEFORE -> {
                int before = t.firstCallOrder().indexOf(a.name());
                int after = t.firstCallOrder().indexOf(a.other());
                yield after >= 0 && (before < 0 || before > after);
            }
            case REACHES_WITHIN -> !t.reached().contains(a.name()) && facts.elapsedMillis() > a.withinMillis();
            // movement only, never what the value says (D7): the field has stood still for too long
            case CHANGES_WITHIN -> facts.unchangedMillis().getOrDefault(a.name(), 0L) > a.withinMillis();
        };
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
