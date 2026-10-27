package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.*;

/** A statement as one short phrase, for saying which statement a decision belongs to. */
public final class StatementText {

    private StatementText() {}

    public static String of(Statement s) {
        if (s == null) return "(nothing)";
        if (s instanceof AltStmt a)
            return "alt ("
                    + io.github.llm4j.loom.runtime.ConditionEvaluator.display(a.getCondition())
                    + ")";
        if (s instanceof LoopStmt l)
            return "loop until ("
                    + io.github.llm4j.loom.runtime.ConditionEvaluator.display(l.getCondition())
                    + ")";
        if (s instanceof ForEachStmt f)
            return (f.isParallel() ? "parallel " : "")
                    + "for each "
                    + f.getItemName()
                    + " in "
                    + f.getCollectionPath();
        if (s instanceof DelegateStmt d) return "delegate " + d.getTargetAgent();
        if (s instanceof BroadcastStmt b) return "broadcast " + b.getTargetAgents();
        if (s instanceof HandoffStmt h) return "handoff " + h.getTargetAgent();
        if (s instanceof RunStmt r) return "run " + r.getTaskName();
        if (s instanceof IntentClassifyStmt c) return "intentclassify " + c.getClassifier();
        if (s instanceof DecideStmt d) return "decide " + d.getDecision();
        if (s instanceof CallStmt c) return "call " + c.getWorkflowName();
        if (s instanceof CheckpointStmt c) return "checkpoint " + c.getName();
        if (s instanceof RewindStmt r) return "rewind to " + r.getTarget();
        if (s instanceof HumanPromptStmt h) return "human_prompt";
        if (s instanceof SetStmt s2) return "set " + s2.getName();
        if (s instanceof ReturnStmt) return "return";
        if (s instanceof HaltStmt) return "halt";
        if (s instanceof NoteStmt) return "note";
        if (s instanceof SleepStmt) return "sleep";
        if (s instanceof ParallelStmt) return "parallel";
        if (s instanceof GuardrailStmt g) return "guardrail (" + g.getType() + ")";
        if (s instanceof ObserveStmt) return "observe";
        return s.getClass().getSimpleName().replaceFirst("Stmt$", "").toLowerCase();
    }
}
