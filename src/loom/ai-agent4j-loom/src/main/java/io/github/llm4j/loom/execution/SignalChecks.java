package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.NotifyStmt;
import io.github.llm4j.loom.ast.ParallelStmt;
import io.github.llm4j.loom.ast.SignalWaitStmt;
import io.github.llm4j.loom.ast.StartStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.SuperviseStmt;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The checks of signals (phase 34c): who may signal whom (a parent only handles it started, checked
 * here and again at run time), names and payloads that can never be sent, a wait with no timeout,
 * two waits for one name that can be active at once (D8), and a workflow that signals or waits for a
 * parent that nothing in the script gives it (a warning: it fails or does nothing when run directly).
 */
final class SignalChecks {

    private SignalChecks() {}

    static void run(ScriptValidator.Checker c) {
        Set<String> started = new HashSet<>();
        for (WorkflowDef w : c.script().getWorkflows())
            StatementWalker.walk(w.getStatements(), s -> {
                if (s instanceof StartStmt st) started.add(st.getWorkflow());
            });
        for (WorkflowDef w : c.script().getWorkflows()) workflow(c, w, started.contains(w.getName()));
    }

    private static void workflow(ScriptValidator.Checker c, WorkflowDef w, boolean isStarted) {
        Set<String> known = new HashSet<>(w.getParameters());
        StatementWalker.walk(w.getStatements(), s -> {
            if (s instanceof StartStmt st) known.add(st.getTarget().name());
        });
        List<NotifyStmt> toParent = new ArrayList<>();
        List<SignalWaitStmt> fromParent = new ArrayList<>();
        StatementWalker.walk(
                w.getStatements(),
                s -> {
                    if (s instanceof NotifyStmt n) {
                        if (n.toParent()) toParent.add(n);
                        else ChildChecks.isHandle(c, known, n.getTarget(), n.getLine(), "notify");
                        name(c, n.getLine(), "notify", n.getName());
                        String text = n.getText();
                        if (text != null && !text.contains("{") && text.length() > Mailbox.MAX_PAYLOAD)
                            c.error(n.getLine(), "notify", "a signal's text is at most " + Mailbox.MAX_PAYLOAD + " characters, and this one has " + text.length()
                                    + ". Send less, or put the data somewhere both can read and signal where it is.");
                    } else if (s instanceof SignalWaitStmt wait) {
                        if (wait.getTarget() == null) fromParent.add(wait);
                        else ChildChecks.isHandle(c, known, wait.getTarget(), wait.getLine(), "wait");
                        name(c, wait.getLine(), "wait signal", wait.getName());
                        if (wait.getTimeoutMillis() == null)
                            c.error(wait.getLine(), "wait signal \"" + wait.getName() + "\"",
                                    "a wait needs a timeout, so nothing waits for ever: wait signal \"" + wait.getName() + "\" timeout 10m -> text");
                    } else if (s instanceof SuperviseStmt sv) {
                        for (SuperviseStmt.SignalRule r : sv.getSignals()) name(c, r.line(), "when signal", r.name());
                    } else if (s instanceof ParallelStmt p) {
                        parallel(c, p);
                    } else if (s instanceof ForEachStmt f && f.isParallel()) {
                        Set<String> inside = new LinkedHashSet<>();
                        StatementWalker.walk(f.getBody(), x -> {
                            if (x instanceof SignalWaitStmt sw) inside.add(sw.getName());
                        });
                        for (String key : inside)
                            c.error(f.getLine(), "parallel for each",
                                    "every item of a parallel for each would wait for the signal \"" + key + "\" at once, so which gets the first message is not decided."
                                            + " Wait for it before the loop, or give each item its own signal.");
                    }
                });
        if (!isStarted) {
            for (NotifyStmt n : toParent)
                c.warn(n.getLine(), "notify parent \"" + n.getName() + "\"",
                        w.getName() + " signals its parent, and nobody in this script starts " + w.getName()
                                + ": run directly there is no parent, and the signal is dropped (recorded, not an error).");
            for (SignalWaitStmt wait : fromParent)
                c.warn(wait.getLine(), "wait signal \"" + wait.getName() + "\"",
                        w.getName() + " waits for the signal \"" + wait.getName() + "\" from its parent, and nobody in this script starts it:"
                                + " run directly it has no parent and fails. Start it with start.");
        }
    }

    private static void name(ScriptValidator.Checker c, int line, String where, String name) {
        String problem = Mailbox.nameProblem(name);
        if (problem != null) c.error(line, where + " \"" + name + "\"", problem);
    }

    /**
     * Two waits for the same name that can be active at once make the first message's receiver
     * depend on timing, so they are refused (D8): in different branches of one parallel, or anywhere
     * inside a parallel for each (every item would wait for the same one).
     */
    private static void parallel(ScriptValidator.Checker c, ParallelStmt p) {
        Set<String> seen = new LinkedHashSet<>();
        for (Statement branch : p.getBody()) {
            Set<String> mine = new LinkedHashSet<>();
            waits(branch, mine);
            for (String key : mine)
                if (!seen.add(key))
                    c.error(branch.getLine(), "parallel",
                            "two branches of one parallel wait for the signal \"" + key + "\", so which of them gets the first message is not decided."
                                    + " Give each branch its own signal name, or wait for it before the parallel.");
        }
    }

    private static void waits(Statement s, Set<String> into) {
        List<Statement> one = List.of(s);
        StatementWalker.walk(one, x -> {
            if (x instanceof SignalWaitStmt w) into.add(w.getName());
        });
    }
}
