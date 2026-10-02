package io.github.llm4j.loom.ast;

import java.util.List;
import java.util.function.Consumer;

/** Visits a statement list and every statement nested in it (branches, loop bodies, handlers), depth first. */
public final class StatementWalker {

    private StatementWalker() { }

    public static void walk(List<Statement> statements, Consumer<Statement> visit) {
        for (Statement s : statements) {
            visit.accept(s);
            for (List<Statement> nested : nested(s)) walk(nested, visit);
        }
    }

    /** The statement lists directly inside a statement. */
    public static List<List<Statement>> nested(Statement s) {
        if (s instanceof AltStmt a) return List.of(a.getIfBranch() == null ? List.of() : a.getIfBranch(), a.getElseBranch() == null ? List.of() : a.getElseBranch());
        if (s instanceof LoopStmt l) return List.of(l.getBody(), l.getOnExhausted());
        if (s instanceof ForEachStmt f) return List.of(f.getBody(), f.getOnExhausted());
        if (s instanceof ParallelStmt p) return List.of(p.getBody());
        if (s instanceof DelegateStmt d) return List.of(d.getOnFailure());
        if (s instanceof GuardrailStmt g) return List.of(g.getBody(), g.getOnViolation());
        if (s instanceof RewindStmt r) return List.of(r.getIfStillFails(), r.getIfBlocked());
        return List.of();
    }

    /** True when any workflow statement, however deep, satisfies the test. */
    public static boolean any(LoomScript script, java.util.function.Predicate<Statement> test) {
        boolean[] found = {false};
        for (WorkflowDef w : script.getWorkflows()) walk(w.getStatements(), s -> { if (test.test(s)) found[0] = true; });
        return found[0];
    }
}
