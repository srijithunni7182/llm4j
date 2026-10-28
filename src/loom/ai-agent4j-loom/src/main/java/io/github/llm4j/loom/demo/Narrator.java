package io.github.llm4j.loom.demo;

import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.HaltStmt;
import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.IntentClassifyStmt;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.NoteStmt;
import io.github.llm4j.loom.ast.ReturnStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.execution.DecisionPoint;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.travel.ObserveReplay;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One plain sentence per step of a run, made from what the interpreter recorded (the statement, the
 * decision it took, the journal entry) and a small table of text by kind of statement, so what it
 * says cannot drift from what the language does.
 */
public final class Narrator {

    private Narrator() {}

    /**
     * @param step the step id; @param label the short name of the step for a path; @param text the
     *     sentence
     */
    public record Line(String step, String label, String text) {}

    public static List<Line> narrate(ObserveReplay.Result r) {
        List<Line> out = new ArrayList<>();
        for (ObserveReplay.Event ev : r.timeline()) {
            Statement st = r.statements().get(ev.step());
            RunJournal.Entry e = r.journal().get(ev.step());
            DecisionPoint d = ev.decision();
            Line line = line(ev.step(), st, e, d);
            // a step can leave two events (the step and the decision it took): say it once
            if (line != null && (out.isEmpty() || !out.get(out.size() - 1).equals(line)))
                out.add(line);
        }
        return out;
    }

    private static Line line(String step, Statement st, RunJournal.Entry e, DecisionPoint d) {
        if (d != null && d.kind().equals("failure"))
            return new Line(
                    step,
                    "failure",
                    "A step failed and a handler took over ("
                            + clip(String.valueOf(d.detail().getOrDefault("handler", d.taken())))
                            + "). The run carries on instead of crashing.");
        if (st instanceof AltStmt && d != null)
            return new Line(
                    step,
                    "alt",
                    "alt: Loom itself checked `"
                            + d.condition()
                            + "`"
                            + operands(d)
                            + ". It is "
                            + d.result()
                            + ", so it took the "
                            + d.taken()
                            + " branch. No model decides this.");
        if (st instanceof LoopStmt && d != null)
            return new Line(
                    step,
                    "loop",
                    "loop: Loom checked `"
                            + d.condition()
                            + "` and chose to "
                            + d.taken()
                            + ". A loop always has a limit.");
        if (st instanceof IntentClassifyStmt c)
            return new Line(
                    step,
                    "classify " + c.getClassifier(),
                    "classify: the classifier "
                            + c.getClassifier()
                            + " answered "
                            + labels(e)
                            + ". It chooses among fixed answers, so what follows is an exact comparison, not a guess at free text.");
        if (st instanceof DelegateStmt dl) {
            if (e != null && e.kind().equals("failed"))
                return new Line(
                        step,
                        "delegate " + dl.getTargetAgent(),
                        "delegate: the agent "
                                + dl.getTargetAgent()
                                + " could not answer ("
                                + clip(String.valueOf(e.value()))
                                + ").");
            return new Line(
                    step,
                    "delegate " + dl.getTargetAgent(),
                    "delegate: the agent "
                            + dl.getTargetAgent()
                            + " was asked"
                            + (dl.getRetryCount() > 0
                                    ? " (retry "
                                            + dl.getRetryCount()
                                            + " is set, so a failed call is tried again)"
                                    : "")
                            + " and answered: \""
                            + clip(e == null ? "" : String.valueOf(e.value()))
                            + "\"");
        }
        if (st instanceof HumanPromptStmt)
            return new Line(
                    step,
                    "ask a person",
                    "ask a person: the run stopped for a human. The answer, \""
                            + clip(e == null ? "" : String.valueOf(e.value()))
                            + "\", is written to the journal, so a real run could wait for it for days and survive a restart.");
        if (st instanceof ReturnStmt)
            return new Line(
                    step, "return", "return: the workflow ends here and hands its value back.");
        if (st instanceof HaltStmt)
            return new Line(step, "halt", "halt: the workflow stops on purpose, with a reason.");
        if (st instanceof NoteStmt)
            return new Line(step, "note", "note: Loom records a line in the run's trail.");
        return null;
    }

    private static String operands(DecisionPoint d) {
        if (d.operands() == null || d.operands().isEmpty()) return "";
        StringBuilder b = new StringBuilder(" (");
        for (int i = 0; i < d.operands().size(); i++) {
            var o = d.operands().get(i);
            b.append(i > 0 ? ", " : "")
                    .append(o.expr())
                    .append(" was \"")
                    .append(clip(o.value()))
                    .append('"');
            if (o.boundBy() != null) b.append(", set by ").append(o.boundBy());
        }
        return b.append(')').toString();
    }

    private static String labels(RunJournal.Entry e) {
        if (e == null || !(e.value() instanceof Map<?, ?> m)) return "(no answer recorded)";
        List<String> parts = new ArrayList<>();
        m.forEach(
                (k, v) -> {
                    String key = String.valueOf(k);
                    if (!key.matches(
                            ".*(_confidence|_probability)|confidence|calibrated|backend|model"))
                        parts.add(key + " = " + v);
                });
        return String.join(", ", parts);
    }

    private static String clip(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 90 ? one.substring(0, 90) + "…" : one;
    }
}
