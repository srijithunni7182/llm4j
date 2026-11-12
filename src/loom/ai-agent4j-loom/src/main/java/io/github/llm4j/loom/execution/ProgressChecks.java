package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.ProgressDecl;
import io.github.llm4j.loom.ast.ReportStmt;
import io.github.llm4j.loom.ast.SchemaDef;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.ValueExpr;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.ArrayList;
import java.util.List;

/**
 * The checks of declared progress (phase 34b, D6): a {@code report} of a field the workflow did not
 * declare, a type that can be seen to be wrong, a {@code report} in a workflow with no declaration,
 * and a declaration that is never reported (a warning). A value that is not a literal (a variable)
 * is checked when it runs.
 */
final class ProgressChecks {

    private ProgressChecks() {}

    static void run(ScriptValidator.Checker c) {
        for (WorkflowDef w : c.script().getWorkflows()) workflow(c, w);
    }

    private static void workflow(ScriptValidator.Checker c, WorkflowDef w) {
        List<ReportStmt> reports = new ArrayList<>();
        StatementWalker.walk(w.getStatements(), s -> {
            if (s instanceof ReportStmt r) reports.add(r);
        });
        ProgressDecl decl = w.getProgress();
        for (ReportStmt r : reports) {
            if (decl == null) {
                c.error(
                        r.getLine(),
                        "report",
                        "report needs the workflow " + w.getName() + " to declare what it reports: workflow " + w.getName()
                                + "(...) progress { stage: string, done: number } { ... }");
                continue;
            }
            r.getValue().fields().forEach((name, value) -> field(c, w, decl, r, name, value));
        }
        if (decl != null && reports.isEmpty())
            c.warn(
                    decl.line(),
                    "progress",
                    "the workflow " + w.getName() + " declares progress and never reports it. Write report { ... } where it moves on,"
                            + " or remove the declaration.");
    }

    private static void field(
            ScriptValidator.Checker c, WorkflowDef w, ProgressDecl decl, ReportStmt r, String name, ValueExpr value) {
        SchemaDef type = decl.fields().get(name);
        if (type == null) {
            c.error(
                    r.getLine(),
                    "report",
                    "report: " + name + " is not a progress field of " + w.getName() + ". It declares: "
                            + String.join(", ", decl.fields().keySet()) + ".");
            return;
        }
        String problem = mismatch(type, value);
        if (problem != null) c.error(r.getLine(), "report", "report: " + name + " is declared " + show(type) + " and was given " + problem + ".");
    }

    /** What is wrong with a literal for this type, or null; anything that is not a literal waits for run time. */
    private static String mismatch(SchemaDef type, ValueExpr value) {
        if (value instanceof ValueExpr.Text t && t.text().contains("{")) return null; // filled in when it runs
        return switch (type.getType()) {
            case STRING -> value instanceof ValueExpr.Num n ? "the number " + n.literal() : value instanceof ValueExpr.Bool b ? "the value " + b.value() : null;
            case NUMBER -> value instanceof ValueExpr.Text t ? "the text \"" + t.text() + "\"" : value instanceof ValueExpr.Bool b ? "the value " + b.value() : null;
            case BOOLEAN -> value instanceof ValueExpr.Text t ? "the text \"" + t.text() + "\"" : value instanceof ValueExpr.Num n ? "the number " + n.literal() : null;
            case ENUM -> value instanceof ValueExpr.Text t
                    ? (type.getEnumValues().contains(t.text()) ? null : "the text \"" + t.text() + "\"")
                    : value instanceof ValueExpr.Ref ? null : "a value that is not one of its words";
            default -> null;
        };
    }

    private static String show(SchemaDef type) {
        return switch (type.getType()) {
            case STRING -> "text (string)";
            case NUMBER -> "number";
            case BOOLEAN -> "true or false (boolean)";
            case ENUM -> "enum" + type.getEnumValues().stream().map(x -> "\"" + x + "\"").toList();
            default -> type.getType().name().toLowerCase();
        };
    }

    /** The error for {@code assert progress.<field> changes within}, or null when the child declares that field. */
    static String assertionProblem(WorkflowDef child, String field) {
        if (child == null) return null;
        if (child.getProgress() == null)
            return "the child " + child.getName() + " declares no progress, so there is nothing to watch. Declare it: workflow " + child.getName()
                    + "(...) progress { " + field + ": number } { ... }";
        if (!child.getProgress().fields().containsKey(field))
            return child.getName() + " declares no progress field " + field + ". It declares: "
                    + String.join(", ", child.getProgress().fields().keySet()) + ".";
        return null;
    }
}
