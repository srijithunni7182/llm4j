package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.StartStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.SuperviseStmt;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pairs a {@code start} with the {@code supervise} of the same handle in the same block (D3, F12):
 * the two are matched by the handle's written form, for example {@code work[t.name]} inside one
 * {@code for each} body. The start reserves the judge's budget of its pair, so a parent that cannot
 * pay for the child and its judge refuses the start instead of leaving a running child that cannot
 * be supervised. Worked out from the script, which is known before it runs.
 */
final class ChildPairing {

    private ChildPairing() {}

    /** The {@code supervise} in the same block as {@code start} that watches the same handle, or null. */
    static SuperviseStmt superviseOf(LoomScript script, StartStmt start) {
        for (WorkflowDef w : script.getWorkflows()) {
            List<Statement> block = blockHolding(w.getStatements(), start);
            if (block == null) continue;
            for (Statement s : block)
                if (s instanceof SuperviseStmt sv
                        && sv.getTarget().describe().equals(start.getTarget().describe())) return sv;
            return null;
        }
        return null;
    }

    /** True when {@code supervise} has a {@code start} of its handle in the same block. */
    static boolean isPaired(LoomScript script, SuperviseStmt supervise) {
        for (WorkflowDef w : script.getWorkflows()) {
            List<Statement> block = blockHolding(w.getStatements(), supervise);
            if (block == null) continue;
            for (Statement s : block)
                if (s instanceof StartStmt st && st.getTarget().describe().equals(supervise.getTarget().describe()))
                    return true;
            return false;
        }
        return false;
    }

    /** Every statement list in a workflow, the outer one and each nested block. */
    static void blocks(List<Statement> statements, Consumer<List<Statement>> visit) {
        visit.accept(statements);
        for (Statement s : statements)
            for (List<Statement> nested : StatementWalker.nested(s)) blocks(nested, visit);
    }

    private static List<Statement> blockHolding(List<Statement> statements, Statement wanted) {
        List<List<Statement>> found = new ArrayList<>();
        blocks(statements, b -> {
            for (Statement s : b) if (s == wanted) found.add(b);
        });
        return found.isEmpty() ? null : found.get(0);
    }
}
