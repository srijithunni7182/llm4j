package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.SuperviseDef;
import io.github.llm4j.loom.graph.GraphNode;
import io.github.llm4j.loom.graph.GraphResult;
import io.github.llm4j.loom.graph.Kinds;
import io.github.llm4j.loom.graph.WorkflowGraph;
import io.github.llm4j.loom.review.model.Location;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code step} entities: one per statement, taken from the graph so each carries the id {@code
 * weave graph} gives it. Also fills the agent facts that count steps. Without a graph there are no
 * steps, and every fact that depends on them stays unknown.
 */
final class StepFacts implements FactProvider {

    @Override
    public String name() {
        return "step";
    }

    @Override
    public void provide(FactInput input, FactBase.Builder out) {
        GraphResult graph = input.review().graph();
        if (graph == null) return;
        Map<String, Long> perAgent = new HashMap<>();
        boolean dynamicTargets = false;
        for (WorkflowGraph w : graph.workflows()) {
            Entity workflow = workflowEntity(out, input, w);
            Map<String, GraphNode> byId = new HashMap<>();
            for (GraphNode n : w.nodes()) byId.put(n.id(), n);
            for (GraphNode n : w.nodes()) {
                if (n.kind().equals(Kinds.START) || n.kind().equals(Kinds.END)) continue;
                Entity e =
                        out.add(
                                "step",
                                "step:" + w.name() + "/" + n.id(),
                                location(input, graph, n));
                out.set(e, "kind", n.kind());
                out.set(e, "label", n.label());
                out.set(e, "line", n.source() == null ? 0L : (long) n.source().line());
                out.set(e, "workflow", workflow);
                int depth = 0;
                boolean loop = false;
                boolean each = false;
                for (GraphNode p = parent(byId, n); p != null; p = parent(byId, p)) {
                    depth++;
                    loop |= p.kind().equals(Kinds.LOOP);
                    each |= p.kind().equals(Kinds.FOREACH);
                }
                out.set(e, "depth", (long) depth);
                out.set(e, "inLoop", loop);
                out.set(e, "inForEach", each);
                long retries = number(n.attrs().get("retry"));
                long timeoutMs = number(n.attrs().get("timeoutMs"));
                out.set(e, "retry", retries > 0);
                out.set(e, "retryCount", retries);
                out.set(e, "timeout", timeoutMs > 0);
                out.set(e, "timeoutMs", timeoutMs);
                out.set(
                        e,
                        "onFailure",
                        w.nodes().stream()
                                .anyMatch(
                                        c ->
                                                n.id().equals(c.parent())
                                                        && "failure".equals(c.branch())));
                boolean isLoop = n.kind().equals(Kinds.LOOP);
                long max = n.bound() == null ? 0 : n.bound();
                out.set(e, "hasMax", isLoop && max > 0);
                out.set(e, "loopMax", isLoop ? max : 0L);
                Object variable = n.attrs().get("variable");
                out.set(e, "bindsVariable", variable == null ? "" : String.valueOf(variable));
                if (n.agent() != null) {
                    Object agent =
                            out.find("agent:" + n.agent())
                                    .map(a -> (Object) a)
                                    .orElse(Unknown.INSTANCE);
                    out.set(e, "agent", agent);
                    perAgent.merge(n.agent(), 1L, Long::sum);
                    if (agent == Unknown.INSTANCE) dynamicTargets = true;
                } else {
                    out.set(e, "agent", Unknown.INSTANCE);
                }
                // a parallel round and a broadcast stand for several agents in one node
                if (n.attrs().get("agents") instanceof List<?> members)
                    for (Object member : members) {
                        perAgent.merge(String.valueOf(member), 1L, Long::sum);
                        if (out.find("agent:" + member).isEmpty()) dynamicTargets = true;
                    }
            }
        }
        Set<String> otherUses = new HashSet<>();
        for (DecisionDef d : input.review().script().getDecisions())
            if (d.getAgent() != null) otherUses.add(d.getAgent());
        for (SuperviseDef s : input.review().script().getSupervisions())
            if (s.getAgent() != null) otherUses.add(s.getAgent());
        for (Entity agent : out.entitiesOf("agent")) {
            String name = (String) agent.get("name");
            long count = perAgent.getOrDefault(name, 0L);
            out.set(agent, "stepCount", count);
            boolean used = count > 0 || otherUses.contains(name);
            out.set(
                    agent,
                    "referenced",
                    used || !dynamicTargets ? (Object) used : Unknown.INSTANCE);
        }
    }

    private static Entity workflowEntity(FactBase.Builder out, FactInput input, WorkflowGraph w) {
        return out.find("workflow:" + w.name())
                .orElseGet(
                        () -> {
                            Entity e =
                                    out.add(
                                            "workflow",
                                            "workflow:" + w.name(),
                                            Location.at(input.review().file(), w.line()));
                            out.set(e, "name", w.name());
                            out.set(e, "line", (long) w.line());
                            return e;
                        });
    }

    private static GraphNode parent(Map<String, GraphNode> byId, GraphNode n) {
        return n.parent() == null ? null : byId.get(n.parent());
    }

    private static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static Location location(FactInput input, GraphResult graph, GraphNode n) {
        int line = n.source() == null ? 0 : n.source().line();
        String file =
                n.source() == null || n.source().file().equals(graph.entry())
                        ? input.review().file()
                        : n.source().file();
        return new Location(file, line, 0, 0, 0, n.id());
    }

    /** The kinds that are statements, for callers that need to tell them from start and end. */
    static boolean isStatement(String kind) {
        return !List.of(Kinds.START, Kinds.END).contains(kind);
    }
}
