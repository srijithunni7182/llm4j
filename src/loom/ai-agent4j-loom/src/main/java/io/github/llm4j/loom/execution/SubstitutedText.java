package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.CallStmt;
import io.github.llm4j.loom.ast.CheckpointStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.HaltStmt;
import io.github.llm4j.loom.ast.HandoffStmt;
import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.IntentClassifyStmt;
import io.github.llm4j.loom.ast.NoteStmt;
import io.github.llm4j.loom.ast.ObserveStmt;
import io.github.llm4j.loom.ast.ReturnStmt;
import io.github.llm4j.loom.ast.RewindStmt;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.SetStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.ValueExpr;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The text of a statement in which the runtime puts a variable's value in place of its name: the
 * payload of a {@code delegate}, {@code broadcast} or {@code handoff}, the message of a {@code
 * human_prompt}, {@code note} or {@code halt}, the texts of a {@code set} and a {@code return}, the
 * arguments of a {@code run}, {@code call} and classifier, the label and expression of an {@code
 * observe}, and what a checkpoint starts with and a rewind carries. The lint reads exactly this,
 * and a test reads {@code HarnessExecutor} to be sure every position it substitutes is here.
 */
public final class SubstitutedText {

    /** The getters the lint scans, as {@code Statement.getter}. */
    public static final Set<String> SCANNED =
            Set.of(
                    "DelegateStmt.getPayload",
                    "BroadcastStmt.getPayload",
                    "HandoffStmt.getPayload",
                    "HumanPromptStmt.getMessage",
                    "NoteStmt.getMessage",
                    "HaltStmt.getMessage",
                    "SetStmt.getValue",
                    "ReturnStmt.getValue",
                    "RunStmt.getArgs",
                    "IntentClassifyStmt.getInputs",
                    "ObserveStmt.getLabel",
                    "ObserveStmt.getExpression",
                    "CallStmt.getArguments",
                    "CheckpointStmt.getStartingWith",
                    "RewindStmt.getCarrying");

    /** The same, by getter name alone, for matching the runtime's own calls. */
    public static final Set<String> SCANNED_GETTERS =
            SCANNED.stream().map(s -> s.substring(s.indexOf('.') + 1)).collect(Collectors.toSet());

    /**
     * Text-like getters of a statement that are names, conditions or settings, not text the runtime
     * substitutes into. A new getter has to be put here or in {@link #SCANNED}.
     */
    public static final Set<String> NOT_SUBSTITUTED =
            Set.of(
                    "AltStmt.getCondition",
                    "LoopStmt.getCondition",
                    "RewindStmt.getCondition",
                    "RewindStmt.getTarget",
                    "BroadcastStmt.getTargetAgents",
                    "BroadcastStmt.getVariableName",
                    "CallStmt.getWorkflowName",
                    "CallStmt.getResultVariable",
                    "CheckpointStmt.getName",
                    "DecideStmt.getDecision",
                    "DecideStmt.getVariable",
                    "DelegateStmt.getTargetAgent",
                    "DelegateStmt.getVariableName",
                    "ForEachStmt.getItemName",
                    "ForEachStmt.getCollectionPath",
                    "GuardrailStmt.getType",
                    "HandoffStmt.getTargetAgent",
                    "HumanPromptStmt.getVariableName",
                    "IntentClassifyStmt.getClassifier",
                    "IntentClassifyStmt.getVariableName",
                    "RunStmt.getTaskName",
                    "RunStmt.getVariableName",
                    "RunStmt.getModel",
                    "SetStmt.getName",
                    "SleepStmt.getText");

    private SubstitutedText() {}

    /** One text the runtime substitutes into, and where it is: {@code delegate payload}, … */
    public record Entry(String position, String text) {}

    /** Every text of the statement that the runtime substitutes into; empty when it has none. */
    public static List<String> of(Statement s) {
        return entries(s).stream().map(Entry::text).toList();
    }

    /** As {@link #of}, each with the position it is in. */
    public static List<Entry> entries(Statement s) {
        List<Entry> out = new ArrayList<>();
        if (s instanceof DelegateStmt d) add(out, "delegate payload", d.getPayload());
        else if (s instanceof BroadcastStmt b) add(out, "broadcast payload", b.getPayload());
        else if (s instanceof HandoffStmt h) add(out, "handoff payload", h.getPayload());
        else if (s instanceof HumanPromptStmt h) add(out, "human_prompt message", h.getMessage());
        else if (s instanceof NoteStmt n) add(out, "note message", n.getMessage());
        else if (s instanceof HaltStmt h) add(out, "halt message", h.getMessage());
        else if (s instanceof SetStmt v) texts(out, "set value", v.getValue());
        else if (s instanceof ReturnStmt r) texts(out, "return value", r.getValue());
        else if (s instanceof RunStmt r) {
            for (RunStmt.Arg a : r.getArgs())
                if (a.kind() == RunStmt.Kind.STRING) add(out, "run argument", a.text());
        } else if (s instanceof IntentClassifyStmt c) {
            for (RunStmt.Arg a : c.getInputs())
                if (a.kind() == RunStmt.Kind.STRING) add(out, "classifier input", a.text());
        } else if (s instanceof ObserveStmt o) {
            add(out, "observe label", o.getLabel());
            add(out, "observe expression", o.getExpression());
        } else if (s instanceof CallStmt c)
            c.getArguments().values().forEach(v -> add(out, "call argument", v));
        else if (s instanceof CheckpointStmt c)
            c.getStartingWith().values().forEach(v -> add(out, "checkpoint value", v));
        else if (s instanceof RewindStmt r)
            r.getCarrying().values().forEach(v -> add(out, "rewind carrying", v));
        return out;
    }

    private static void add(List<Entry> out, String position, String text) {
        if (text != null) out.add(new Entry(position, text));
    }

    /** The quoted texts inside a value; a reference is not text and keeps its type. */
    private static void texts(List<Entry> out, String position, ValueExpr value) {
        if (value instanceof ValueExpr.Text t) add(out, position, t.text());
        else if (value instanceof ValueExpr.Obj o)
            o.fields().values().forEach(v -> texts(out, position, v));
    }
}
