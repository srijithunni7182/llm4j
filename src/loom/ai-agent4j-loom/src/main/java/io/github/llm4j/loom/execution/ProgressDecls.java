package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ProgressDecl;
import io.github.llm4j.loom.ast.ReportStmt;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;

/** Finds the {@code progress} declaration that governs a {@code report}: that of the workflow it is written in. */
final class ProgressDecls {

    private ProgressDecls() {}

    /** The declaration of the workflow holding {@code report}, or null when it declares none. */
    static ProgressDecl of(LoomScript script, ReportStmt report) {
        WorkflowDef w = workflowOf(script, report);
        return w == null ? null : w.getProgress();
    }

    /** The workflow {@code report} is written in, or null. */
    static WorkflowDef workflowOf(LoomScript script, ReportStmt report) {
        for (WorkflowDef w : script.getWorkflows()) {
            boolean[] found = {false};
            StatementWalker.walk(w.getStatements(), s -> {
                if (s == report) found[0] = true;
            });
            if (found[0]) return w;
        }
        return null;
    }
}
