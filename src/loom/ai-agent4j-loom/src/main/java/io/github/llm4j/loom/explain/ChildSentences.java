package io.github.llm4j.loom.explain;

import io.github.llm4j.loom.ast.BudgetDef;
import io.github.llm4j.loom.ast.CancelStmt;
import io.github.llm4j.loom.ast.StartStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.SteerStmt;
import io.github.llm4j.loom.ast.SuperviseDef;
import io.github.llm4j.loom.ast.SuperviseStmt;
import io.github.llm4j.loom.ast.WaitStmt;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain sentences for the statements that start and watch other workflows (phase 33): what a
 * reader of {@code weave explain} needs to know about each, in the words the rest of the page
 * uses.
 */
final class ChildSentences {

    private ChildSentences() {}

    /** The sentence for a child statement, or null when the statement is not one. */
    static String of(Statement s) {
        if (s instanceof StartStmt x) return start(x);
        if (s instanceof WaitStmt x) return x.isAny() ? waitAny(x) : wait(x);
        if (s instanceof CancelStmt x) return cancel(x);
        if (s instanceof SteerStmt x)
            return "The agent " + x.getAgent() + " of " + x.getTarget().describe()
                    + " is told, in these words: \"" + x.getText() + "\"";
        if (s instanceof SuperviseStmt x) return supervise(x);
        if (s instanceof io.github.llm4j.loom.ast.NotifyStmt x)
            return "The workflow sends " + (x.toParent() ? "its parent" : x.getTarget().describe()) + " the signal \"" + x.getName() + "\""
                    + (x.isSticky() ? ", a latch that stays set until it is replaced" : ", a message that is read once")
                    + (x.getText() == null ? "" : ", with text that the receiver reads as untrusted")
                    + ". A signal causes nothing by itself.";
        if (s instanceof io.github.llm4j.loom.ast.SignalWaitStmt x)
            return "The workflow waits for the signal \"" + x.getName() + "\" from " + (x.getTarget() == null ? "its parent" : x.getTarget().describe())
                    + (x.getTimeoutMillis() == null ? "" : " for at most " + duration(x.getTimeoutMillis()))
                    + ". While it waits it holds no thread, and what arrives is read as untrusted text.";
        if (s instanceof io.github.llm4j.loom.ast.ReportStmt x)
            return "The workflow reports where it is: " + String.join(", ", x.getValue().fields().keySet())
                    + ". Whoever started it can read the latest report, as text it cannot trust.";
        return null;
    }

    private static String start(StartStmt x) {
        StringBuilder b =
                new StringBuilder("The workflow ")
                        .append(x.getWorkflow())
                        .append(" is started as a run of its own and the script goes on at once");
        if (x.getTimeoutMillis() != null)
            b.append(", with ").append(duration(x.getTimeoutMillis())).append(" to finish");
        if (x.getBudget() != null) b.append(", spending at most ").append(budget(x.getBudget()));
        return b.append("; the handle is kept as ").append(x.getTarget().describe()).toString();
    }

    private static String waitAny(WaitStmt x) {
        return "The script waits for whichever happens first among " + x.getTarget().describe() + ": a signal from a child, a child finishing, or "
                + (x.getTimeoutMillis() == null ? "the timeout" : duration(x.getTimeoutMillis()) + " passing")
                + ". The first return is recorded, so a replay returns the same event; a signal is taken once and a finished child is reported once."
                + (x.getResultVariable() == null ? "" : " The event goes to " + x.getResultVariable() + ", as untrusted text.");
    }

    private static String wait(WaitStmt x) {
        StringBuilder b =
                new StringBuilder("The script waits for ")
                        .append(x.isAll() ? "every child in " : "")
                        .append(x.getTarget().describe());
        if (x.getTimeoutMillis() != null)
            b.append(" for at most ").append(duration(x.getTimeoutMillis()));
        if (x.getResultVariable() != null)
            b.append(", keeping what comes back as ").append(x.getResultVariable());
        return b.toString();
    }

    private static String cancel(CancelStmt x) {
        StringBuilder b = new StringBuilder("The child ").append(x.getTarget().describe());
        if (x.getRewindCheckpoint() != null)
            b.append(" is sent back to its checkpoint ")
                    .append(x.getRewindCheckpoint())
                    .append(", at most ")
                    .append(x.getRewindAtMost())
                    .append(" times");
        else
            b.append(" is cancelled")
                    .append(x.getOutcome() == null ? "" : " (" + x.getOutcome().phrase() + ")");
        if (x.getReason() != null) b.append(", saying: \"").append(x.getReason()).append('"');
        return b.append("; an effect already under way finishes first").toString();
    }

    private static String supervise(SuperviseStmt x) {
        List<String> rules = new ArrayList<>();
        for (SuperviseStmt.Counted r : x.getCounted())
            rules.add(
                    "when "
                            + r.counter().word()
                            + " "
                            + r.comparison().symbol()
                            + " "
                            + (r.money() != null
                                    ? "$" + r.money().toPlainString()
                                    : r.counter().isDuration()
                                            ? duration(r.number())
                                            : String.valueOf(r.number()))
                            + ": "
                            + actions(r.actions()));
        for (SuperviseStmt.CodeAssertion a : x.getCode())
            rules.add(codeAssertion(a) + ": " + actions(a.actions()));
        for (SuperviseStmt.Judged j : x.getJudged())
            rules.add(
                    "a judge ("
                            + j.evaluator()
                            + ") is asked, at most "
                            + j.max()
                            + " times, every "
                            + (j.everyMillis() != null ? duration(j.everyMillis()) : j.everySteps() + " steps")
                            + ", whether \""
                            + j.text()
                            + "\""
                            + (j.seeingContent() ? " (it sees content)" : " (it sees facts only)")
                            + ": "
                            + actions(j.actions()));
        return "While " + x.getTarget().describe() + " runs, it is watched: " + String.join("; ", rules);
    }

    private static String codeAssertion(SuperviseStmt.CodeAssertion a) {
        return switch (a.kind()) {
            case NEVER_CALLS -> "the child may never call " + a.name() + " (the call is refused)";
            case CALLS_AT_MOST -> "the child may call " + a.name() + " at most " + a.limit() + " times";
            case CALLS_BEFORE -> "the child must call " + a.name() + " before " + a.other();
            case REACHES_WITHIN ->
                    "the child must reach the checkpoint " + a.name() + " within " + duration(a.withinMillis());
            case CHANGES_WITHIN ->
                    "the child's reported " + a.name() + " must change at least every " + duration(a.withinMillis())
                            + " of running time (only whether it moves is looked at, never what it says)";
        };
    }

    private static String actions(List<SuperviseDef.Action> actions) {
        List<String> parts = new ArrayList<>();
        for (SuperviseDef.Action a : actions)
            parts.add(
                    switch (a.kind()) {
                        case STEER -> "tell it \"" + a.text() + "\"";
                        case WRAP_UP -> "ask it to wrap up";
                        case CANCEL -> "cancel it" + (a.outcome() == null ? "" : " (" + a.outcome().phrase() + ")");
                    });
        return String.join(", then ", parts);
    }

    private static String budget(BudgetDef b) {
        List<String> parts = new ArrayList<>();
        if (b.getTokens() != null) parts.add(b.getTokens() + " tokens");
        if (b.getCalls() != null) parts.add(b.getCalls() + " calls");
        if (b.getCost() != null) parts.add("$" + b.getCost().toPlainString());
        return parts.isEmpty() ? "what its budget allows" : String.join(" and ", parts);
    }

    private static String duration(long millis) {
        if (millis % 3_600_000 == 0) return millis / 3_600_000 + " hours";
        if (millis % 60_000 == 0) return millis / 60_000 + " minutes";
        if (millis % 1000 == 0) return millis / 1000 + " seconds";
        return millis + " ms";
    }
}
