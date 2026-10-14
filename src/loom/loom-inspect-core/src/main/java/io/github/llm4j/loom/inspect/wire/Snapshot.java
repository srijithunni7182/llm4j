package io.github.llm4j.loom.inspect.wire;

import java.util.List;
import java.util.Map;

/**
 * What a run is that events do not say: its workflow graph and where its decisions stand. Sent when a run starts and again after
 * every reconnect, so a console that came up late (or lost an outbox segment) still draws the run correctly.
 */
public record Snapshot(String client, String instance, String traceId, String runId, String workflow, Map<String, Object> graph,
                       List<Map<String, Object>> ladders, Map<String, io.github.llm4j.loom.inspect.state.InspectRules> inspect,
                       Map<String, String> models, List<Map<String, Object>> security) {

    /** Without what the agents can reach (a sender from before the security view). */
    public Snapshot(String client, String instance, String traceId, String runId, String workflow, Map<String, Object> graph, List<Map<String, Object>> ladders,
                    Map<String, io.github.llm4j.loom.inspect.state.InspectRules> inspect, Map<String, String> models) {
        this(client, instance, traceId, runId, workflow, graph, ladders, inspect, models, List.of());
    }

    public Snapshot(String client, String instance, String traceId, String runId, String workflow, Map<String, Object> graph, List<Map<String, Object>> ladders) {
        this(client, instance, traceId, runId, workflow, graph, ladders, Map.of(), Map.of());
    }

    public Snapshot(String client, String instance, String traceId, String runId, String workflow, Map<String, Object> graph, List<Map<String, Object>> ladders,
                    Map<String, io.github.llm4j.loom.inspect.state.InspectRules> inspect) {
        this(client, instance, traceId, runId, workflow, graph, ladders, inspect, Map.of());
    }

    public Snapshot {
        inspect = inspect == null ? Map.of() : Map.copyOf(inspect);
        models = models == null ? Map.of() : Map.copyOf(models); // the model each agent uses, so a server with a price table can cost its steps
        graph = graph == null ? Map.of() : graph;
        ladders = ladders == null ? List.of() : List.copyOf(ladders);
        security = security == null ? List.of() : List.copyOf(security); // what each agent can reach, from the static audit of the script
    }
}
