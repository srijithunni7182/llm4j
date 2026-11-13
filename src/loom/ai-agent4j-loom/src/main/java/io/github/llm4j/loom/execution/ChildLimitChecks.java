package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.CancelStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.StartStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.SteerStmt;
import io.github.llm4j.loom.ast.SuperviseDef;
import io.github.llm4j.loom.ast.SuperviseStmt;
import io.github.llm4j.loom.ast.WaitStmt;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.List;
import java.util.function.Consumer;

/**
 * The warnings of phase 34a (F9, F12) about what a block of child statements can be proven to do:
 * start more children at once than {@code max_children} allows, steer one agent twice in a row,
 * steer twice in one rule, or supervise with a judge a child it did not start in the same block.
 * They are warnings, not errors: each says what it could prove and what to do.
 */
final class ChildLimitChecks {

    private ChildLimitChecks() {}

    static void workflow(ScriptValidator.Checker c, LoomScript script, WorkflowDef w) {
        int max = script.getMaxChildren() != null ? script.getMaxChildren() : ChildLimits.DEFAULT_MAX_CHILDREN;
        ChildPairing.blocks(
                w.getStatements(),
                block -> {
                    startsAtOnce(c, block, max);
                    adjacentSteers(c, block);
                });
        StatementWalker.walk(
                w.getStatements(),
                s -> {
                    if (s instanceof SuperviseStmt sv) {
                        stepsPerTick(c, sv);
                        if (!sv.getJudged().isEmpty() && !ChildPairing.isPaired(script, sv))
                            c.warn(
                                    sv.getLine(),
                                    "supervise " + sv.getTarget().describe(),
                                    "this supervise has a judge and its handle was not started in the same block, so the start could not"
                                            + " reserve the judge's budget. The judge is paid for when this runs, and if the budget cannot"
                                            + " cover it the child is already running and unsupervised. Start and supervise it in the same block.");
                    }
                });
    }

    // ---- start more children at once than max_children allows ----

    private static void startsAtOnce(ScriptValidator.Checker c, List<Statement> block, int max) {
        int running = 0;
        for (Statement s : block) {
            if (s instanceof StartStmt st) {
                running++;
                if (running == max + 1)
                    c.warn(
                            st.getLine(),
                            "start " + st.getWorkflow(),
                            running + " children can be running at once here, past max_children: " + max
                                    + ". A start past the limit fails when it runs. Wait for or cancel one first, or raise max_children.");
                continue;
            }
            int bound = loopBound(s);
            if (bound > 0 && !frees(s)) {
                int perRound = (int) directStarts(s);
                if (perRound > 0 && running + (long) bound * perRound > max)
                    c.warn(
                            s.getLine(),
                            "loop",
                            "this loop can start up to " + (running + (long) bound * perRound)
                                    + " children with none waited on inside it, past max_children: " + max
                                    + ". A start past the limit fails when it runs. Wait for each child inside the loop, lower its max, or raise max_children.");
                running += (int) Math.min((long) bound * perRound, Integer.MAX_VALUE / 2);
                continue;
            }
            if (frees(s)) running = 0; // a wait or a cancel may have freed a slot: nothing more is provable
        }
    }

    /** The loop's {@code max}, or 0 for a statement that is not a bounded loop. */
    private static int loopBound(Statement s) {
        if (s instanceof ForEachStmt f) return Math.max(f.getMax(), 0);
        if (s instanceof LoopStmt l) return Math.max(l.getMaxIterations(), 0);
        return 0;
    }

    private static long directStarts(Statement loop) {
        long n = 0;
        for (List<Statement> body : StatementWalker.nested(loop).subList(0, 1))
            for (Statement s : body) if (s instanceof StartStmt) n++;
        return n;
    }

    /** True when the statement or anything in it waits for or cancels a child. */
    private static boolean frees(Statement s) {
        boolean[] any = {s instanceof WaitStmt || s instanceof CancelStmt};
        for (List<Statement> nested : StatementWalker.nested(s))
            StatementWalker.walk(nested, n -> {
                if (n instanceof WaitStmt || n instanceof CancelStmt) any[0] = true;
            });
        return any[0];
    }

    // ---- steers ----

    private static void adjacentSteers(ScriptValidator.Checker c, List<Statement> block) {
        for (int i = 1; i < block.size(); i++) {
            if (block.get(i - 1) instanceof SteerStmt a
                    && block.get(i) instanceof SteerStmt b
                    && a.getTarget().describe().equals(b.getTarget().describe())
                    && a.getAgent().equals(b.getAgent()))
                c.warn(
                        b.getLine(),
                        "steer " + b.getTarget().describe() + " " + b.getAgent(),
                        "two steers to " + b.getAgent() + " of " + b.getTarget().describe()
                                + " in one block, one right after the other: the agent takes the second as the newer instruction and the"
                                + " first is overridden. Send one, or put a step between them.");
        }
    }

    private static void stepsPerTick(ScriptValidator.Checker c, SuperviseStmt sv) {
        sv.getCounted().forEach(r -> twoSteers(c, r.actions(), r.line(), sv));
        sv.getCode().forEach(r -> twoSteers(c, r.actions(), r.line(), sv));
        sv.getJudged().forEach(r -> twoSteers(c, r.actions(), r.line(), sv));
    }

    private static void twoSteers(
            ScriptValidator.Checker c, List<SuperviseDef.Action> actions, int line, SuperviseStmt sv) {
        if (actions.stream().filter(a -> a.kind() == SuperviseDef.Do.STEER).count() > 1)
            c.warn(
                    line,
                    "supervise " + sv.getTarget().describe(),
                    "this rule steers more than once, but only one steer an agent is delivered a tick, so the others are skipped."
                            + " Keep one steer in the rule.");
    }
}
