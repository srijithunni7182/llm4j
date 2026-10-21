package io.github.llm4j.loom.inspect.wire;

import java.util.List;
import java.util.Map;

/**
 * Sent every few seconds, so a quiet process can be told from a dead one, and so the Processes page
 * can show how far behind it is.
 *
 * @param at an ISO-8601 instant
 * @param events how many records the process has sent since it started (the server turns this into
 *     a rate)
 * @param backlog records still in the process's outbox, not yet acknowledged
 * @param lost records the outbox dropped (over its cap) before they were sent
 * @param ladders each live run's decision ladders, by trace id; they change by polling a store, not
 *     by events, so they ride here
 */
public record Heartbeat(
        String client,
        String instance,
        String at,
        long events,
        long backlog,
        long lost,
        Map<String, List<Map<String, Object>>> ladders) {

    public Heartbeat {
        ladders = ladders == null ? Map.of() : Map.copyOf(ladders);
    }

    public Heartbeat(String client, String instance, String at) {
        this(client, instance, at, 0, 0, 0, Map.of());
    }
}
