package io.github.llm4j.loom.ast;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code supervise handle [budget { ... }] { rules }}: watches one started workflow for as long as
 * it runs (D21). Distinct from the top-level {@link SuperviseDef}, which watches an agent. Three
 * kinds of rule, evaluated in this order each tick (D30): counted rules, assertions in code, judged
 * assertions.
 */
public final class SuperviseStmt extends LocatedStatement {

    /** The closed set of runtime counters a rule may watch (D23). */
    public enum Counter {
        TOKENS("tokens"),
        COST("cost"),
        CALLS("calls"),
        ELAPSED("elapsed"),
        IDLE("idle");

        private final String word;

        Counter(String word) {
            this.word = word;
        }

        /** As written in a script. */
        public String word() {
            return word;
        }

        /** The counter written as {@code word}, or null. */
        public static Counter ofWord(String word) {
            for (Counter c : values()) if (c.word.equals(word)) return c;
            return null;
        }

        /** True for the counters measured in time. */
        public boolean isDuration() {
            return this == ELAPSED || this == IDLE;
        }
    }

    /** How a counter is compared. */
    public enum Comparison {
        GREATER(">"),
        GREATER_OR_EQUAL(">="),
        LESS("<"),
        LESS_OR_EQUAL("<="),
        EQUAL("==");

        private final String symbol;

        Comparison(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }

        /** True when {@code left} compares to {@code right} as this comparison says. */
        public boolean holds(java.math.BigDecimal left, java.math.BigDecimal right) {
            int c = left.compareTo(right);
            return switch (this) {
                case GREATER -> c > 0;
                case GREATER_OR_EQUAL -> c >= 0;
                case LESS -> c < 0;
                case LESS_OR_EQUAL -> c <= 0;
                case EQUAL -> c == 0;
            };
        }
    }

    /** The assertions that are checked in code (D24). */
    public enum AssertionKind {
        NEVER_CALLS,
        CALLS_AT_MOST,
        CALLS_BEFORE,
        REACHES_WITHIN
    }

    /**
     * {@code when counter comparison value { actions }}. The value is a number (tokens, calls), a
     * duration in milliseconds (elapsed, idle) or money (cost); exactly one of {@code number} and
     * {@code money} is set.
     */
    public record Counted(
            Counter counter,
            Comparison comparison,
            Long number,
            BigDecimal money,
            boolean once,
            List<SuperviseDef.Action> actions,
            int line) {
        public Counted {
            actions = List.copyOf(actions);
        }
    }

    /**
     * An assertion checked in code. {@code name} is the tool, agent or task for the calls forms and
     * the checkpoint for {@code reaches}; {@code other} is the second name of {@code calls A before
     * B}; {@code limit} is N of {@code at most N times}; {@code withinMillis} is the deadline of
     * {@code reaches}.
     */
    public record CodeAssertion(
            AssertionKind kind,
            String name,
            String other,
            Integer limit,
            Long withinMillis,
            boolean once,
            List<SuperviseDef.Action> actions,
            int line) {
        public CodeAssertion {
            actions = List.copyOf(actions);
        }
    }

    /**
     * {@code assert "text" using Evaluator [seeing content] every 2m|N steps max N { actions }}
     * (D25). Exactly one of {@code everyMillis} and {@code everySteps} is set.
     */
    public record Judged(
            String text,
            String evaluator,
            boolean seeingContent,
            Long everyMillis,
            Integer everySteps,
            int max,
            boolean once,
            List<SuperviseDef.Action> actions,
            int line) {
        public Judged {
            actions = List.copyOf(actions);
        }
    }

    private final HandleRef target;
    private BudgetDef budget;
    private final List<Counted> counted = new ArrayList<>();
    private final List<CodeAssertion> code = new ArrayList<>();
    private final List<Judged> judged = new ArrayList<>();

    public SuperviseStmt(HandleRef target) {
        this.target = target;
    }

    public HandleRef getTarget() {
        return target;
    }

    /** What the judges of this block may spend (D27), or null. */
    public BudgetDef getBudget() {
        return budget;
    }

    public void setBudget(BudgetDef budget) {
        this.budget = budget;
    }

    public List<Counted> getCounted() {
        return counted;
    }

    public List<CodeAssertion> getCode() {
        return code;
    }

    public List<Judged> getJudged() {
        return judged;
    }
}
