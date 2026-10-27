package io.github.llm4j.loom.wtest;

import io.github.llm4j.loom.graph.GraphNode;
import io.github.llm4j.loom.graph.GraphResult;
import io.github.llm4j.loom.graph.WorkflowGraph;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The names a test may use for a step: the label {@code weave graph} shows ({@code delegate
 * Replier}), the node id ({@code n7}) and the kind ({@code human_prompt}). {@code intentclassify X}
 * is accepted for {@code classify X}.
 */
final class Labels {

    private Labels() {}

    /** The step names of the script's workflows, for {@code --steps} and for checking a case. */
    static Set<String> known(GraphResult graph) {
        Set<String> out = new LinkedHashSet<>();
        for (WorkflowGraph w : graph.workflows())
            for (GraphNode n : w.nodes()) {
                if (n.kind().equals("start") || n.kind().equals("end")) continue;
                out.add(n.label());
                out.add(n.id());
                out.add(n.kind());
                if (n.kind().equals("classify")) {
                    out.add(n.label().replaceFirst("^classify ", "intentclassify "));
                    out.add("intentclassify");
                }
                if (n.kind().equals("human_prompt")) out.add("ask a person");
            }
        return out;
    }

    /** True when the step name a test wrote refers to the step that ran, named by its label. */
    static boolean matches(String token, String ran) {
        String t = canonical(token);
        String r = canonical(ran);
        if (t.equals(r)) return true;
        // a bare kind names every step of that kind
        int space = r.indexOf(' ');
        return space > 0 && t.equals(r.substring(0, space))
                || t.equals("human_prompt") && r.equals("ask a person");
    }

    private static String canonical(String s) {
        String t = s.strip();
        if (t.startsWith("intentclassify"))
            return "classify" + t.substring("intentclassify".length());
        return t;
    }
}
