package io.github.llm4j.loom.ast;

import java.util.Optional;

/**
 * Who wrote the list a {@code for each} walks over: the agent or extractor step whose variable the
 * list's path starts with. A list a model wrote is the one whose length nobody controls, so it is the
 * one that needs a bound (docs/specs/25-reason-then-extract, R7).
 */
public final class ListProducers {

    /** A step that wrote a list: {@code agent} or {@code extractor}, its name, its line. */
    public record Producer(String kind, String name, int line) {}

    private ListProducers() {}

    /** The model step that wrote {@code path}'s list in this workflow, if one did. */
    public static Optional<Producer> of(WorkflowDef workflow, String path) {
        String head = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
        Producer[] found = {null};
        StatementWalker.walk(
                workflow.getStatements(),
                s -> {
                    if (found[0] != null) return;
                    if (s instanceof DelegateStmt d && head.equals(d.getVariableName()))
                        found[0] = new Producer("agent", d.getTargetAgent(), d.getLine());
                    else if (s instanceof ExtractStmt x && head.equals(x.getVariableName()))
                        found[0] = new Producer("extractor", x.getExtractor(), x.getLine());
                });
        return Optional.ofNullable(found[0]);
    }

    /** The model step that wrote the list {@code each} walks over, searching every workflow. */
    public static Optional<Producer> of(LoomScript script, ForEachStmt each) {
        for (WorkflowDef w : script.getWorkflows()) {
            boolean[] inside = {false};
            StatementWalker.walk(w.getStatements(), s -> inside[0] |= s == each);
            if (inside[0]) return of(w, each.getCollectionPath());
        }
        return Optional.empty();
    }
}
