package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.SuperviseStmt;

/**
 * When a judged assertion may ask its judge (phase 33g, D26): after the first interval and then
 * every interval of the child's running time (or every N of its journaled steps), never more than
 * {@code max} times in all, and never when the child has done nothing since the last judgement. No
 * clock and no model, so the schedule is tested exactly.
 */
public final class JudgeSchedule {

    /** What to do at this look. */
    public enum Decision {
        /** Ask the judge now. */
        ASK,
        /** The interval has not passed. */
        NOT_YET,
        /** The interval has passed but the child has done nothing: skipped, no model call. */
        SKIP_NO_PROGRESS,
        /** The ceiling was just reached: say "judge limit reached" once. */
        LIMIT_REACHED,
        /** The judge's own budget was just used up: say "judge budget used up" once. */
        BUDGET_USED_UP,
        /** The judge has stopped for good (it said why when it did). */
        STOPPED
    }

    private final SuperviseStmt.Judged rule;
    private int asked;
    private long lastAskedMillis;
    private int lastAskedSteps;
    private long lastSignature;
    private boolean limitSaid;
    private boolean budgetUsedUp;
    private boolean budgetSaid;

    /**
     * @param rule the judged assertion
     * @param signatureAtStart a number that changes whenever the child does something, as it was
     *     when the block began to watch
     */
    public JudgeSchedule(SuperviseStmt.Judged rule, long signatureAtStart) {
        this(rule, signatureAtStart, 0);
    }

    /** As above, for a block that is watching again after a restart with {@code askedBefore} judgements already made. */
    public JudgeSchedule(SuperviseStmt.Judged rule, long signatureAtStart, int askedBefore) {
        this.rule = rule;
        this.lastSignature = signatureAtStart;
        this.asked = askedBefore;
    }

    /** The judge's own budget has nothing left. */
    public void budgetUsedUp() {
        budgetUsedUp = true;
    }

    /**
     * @param runningMillis the child's running time
     * @param steps how many steps the child has journaled
     * @param signature a number that changes whenever the child does something
     */
    public Decision decide(long runningMillis, int steps, long signature) {
        if (budgetUsedUp) {
            if (budgetSaid) return Decision.STOPPED;
            budgetSaid = true;
            return Decision.BUDGET_USED_UP;
        }
        if (asked >= rule.max()) {
            if (limitSaid) return Decision.STOPPED;
            limitSaid = true;
            return Decision.LIMIT_REACHED;
        }
        boolean due =
                rule.everyMillis() != null
                        ? runningMillis - lastAskedMillis >= rule.everyMillis()
                        : steps - lastAskedSteps >= rule.everySteps();
        if (!due) return Decision.NOT_YET;
        return signature == lastSignature ? Decision.SKIP_NO_PROGRESS : Decision.ASK;
    }

    /** The judge was asked at this look. */
    public void asked(long runningMillis, int steps, long signature) {
        asked++;
        lastAskedMillis = runningMillis;
        lastAskedSteps = steps;
        lastSignature = signature;
    }

    /** How many times the judge has been asked. */
    public int askedCount() {
        return asked;
    }
}
