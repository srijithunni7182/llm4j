package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.ast.ReturnStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.review.model.Location;
import java.util.List;

/** The {@code workflow} entities. */
final class WorkflowFacts implements FactProvider {

    @Override
    public String name() {
        return "workflow";
    }

    @Override
    public void provide(FactInput input, FactBase.Builder out) {
        String file = input.review().file();
        for (WorkflowDef w : input.review().script().getWorkflows()) {
            Entity e =
                    out.add("workflow", "workflow:" + w.getName(), Location.at(file, w.getLine()));
            long[] count = {0};
            boolean[] ret = {false};
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        count[0]++;
                        if (s instanceof ReturnStmt) ret[0] = true;
                    });
            out.set(e, "name", w.getName());
            out.set(e, "line", (long) w.getLine());
            out.set(e, "stepCount", count[0]);
            out.set(e, "maxDepth", (long) depth(w.getStatements()));
            out.set(e, "hasReturn", ret[0]);
        }
    }

    private static int depth(List<Statement> statements) {
        int max = 0;
        for (Statement s : statements) {
            int inner = 0;
            for (List<Statement> nested : StatementWalker.nested(s))
                inner = Math.max(inner, depth(nested));
            max = Math.max(max, 1 + inner);
        }
        return max;
    }
}
