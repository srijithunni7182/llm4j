package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.HaltStmt;
import io.github.llm4j.loom.ast.HandoffStmt;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.ParallelStmt;
import io.github.llm4j.loom.ast.ReturnStmt;
import io.github.llm4j.loom.ast.RewindStmt;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks for the statements that end or shape a run: {@code halt}, {@code return} and {@code x is
 * ok}.
 *
 * <ul>
 *   <li>{@code x is ok} asks whether a {@code run} step did what it was for, so {@code x} must be
 *       the result of one (an agent's answer, a person's reply and a parameter have no such thing
 *       as "ok");
 *   <li>a {@code return} cannot sit inside {@code parallel} or {@code for each}: which branch's
 *       value would the workflow hand back?;
 *   <li>a statement after a {@code halt}, {@code return} or {@code handoff} in the same block never
 *       runs.
 * </ul>
 */
final class ControlChecks {

    private static final Pattern IS_OK = Pattern.compile("(\\S+) is (?:not )?ok");

    private ControlChecks() {}

    static void run(ScriptValidator.Checker c) {
        for (WorkflowDef w : c.script().getWorkflows()) {
            Set<String> runResults = new HashSet<>();
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (s instanceof RunStmt r) runResults.add(head(r.getVariableName()));
                    });
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        String condition =
                                s instanceof AltStmt a
                                        ? a.getCondition()
                                        : s instanceof LoopStmt l
                                                ? l.getCondition()
                                                : s instanceof RewindStmt r
                                                        ? r.getCondition()
                                                        : null;
                        if (condition == null) return;
                        Matcher m = IS_OK.matcher(condition.strip());
                        if (m.matches() && !runResults.contains(head(m.group(1)))) {
                            c.error(
                                    s.getLine(),
                                    "(" + condition + ")",
                                    "\"is ok\" asks about the result of a run step, and "
                                            + head(m.group(1))
                                            + " is not set by one in workflow "
                                            + w.getName()
                                            + ". Compare an agent's answer with == instead.");
                        }
                    });
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        List<Statement> inner =
                                s instanceof ParallelStmt p
                                        ? p.getBody()
                                        : s instanceof ForEachStmt f ? f.getBody() : null;
                        if (inner == null) return;
                        StatementWalker.walk(
                                inner,
                                t -> {
                                    if (t instanceof ReturnStmt) {
                                        c.error(
                                                t.getLine(),
                                                "return",
                                                "a return inside "
                                                        + (s instanceof ParallelStmt
                                                                ? "parallel"
                                                                : "for each")
                                                        + " cannot say which branch's value the workflow hands back: set a variable in the branches and return after them");
                                    }
                                });
                    });
            unreachable(w.getStatements(), c, w);
        }
    }

    /**
     * Every block, nested ones included: a statement after one that ends the path is never reached.
     */
    private static void unreachable(
            List<Statement> block, ScriptValidator.Checker c, WorkflowDef w) {
        for (int i = 0; i < block.size(); i++) {
            Statement s = block.get(i);
            if (i + 1 < block.size()
                    && (s instanceof HaltStmt
                            || s instanceof ReturnStmt
                            || s instanceof HandoffStmt)) {
                String what =
                        s instanceof HaltStmt
                                ? "halt"
                                : s instanceof ReturnStmt ? "return" : "handoff";
                c.warn(
                        block.get(i + 1).getLine(),
                        "workflow " + w.getName(),
                        "this statement is never reached: the "
                                + what
                                + " on line "
                                + s.getLine()
                                + " ends the workflow before it");
            }
            if (s instanceof ParallelStmt)
                continue; // its statements are separate branches, not a sequence
            for (List<Statement> nested : StatementWalker.nested(s)) unreachable(nested, c, w);
        }
    }

    private static String head(String name) {
        if (name == null) return "";
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
