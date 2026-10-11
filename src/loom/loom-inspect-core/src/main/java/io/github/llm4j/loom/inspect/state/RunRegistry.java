package io.github.llm4j.loom.inspect.state;

import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Every run a console knows about, keyed by the reporting process's name and the run's trace id. */
public final class RunRegistry {

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    /** Applies log records from one resource (one process instance). Records without a trace id are ignored. */
    public void applyLogs(Resource resource, List<LogRec> records) {
        for (LogRec r : records) {
            if (r.traceId() == null) continue;
            state(resource.serviceName(), r.traceId()).apply(resource.instanceId(), r);
        }
    }

    /** Applies finished spans from one resource. */
    public void applySpans(Resource resource, List<SpanRec> spans) {
        for (SpanRec s : spans) {
            if (s.traceId() == null) continue;
            state(resource.serviceName(), s.traceId()).apply(s);
        }
    }

    /** Gives a run its static graph (the workflows of its script), before or after its first event. */
    public void setGraph(String client, String traceId, Map<String, Object> graph) {
        state(client, traceId).setGraph(graph);
    }

    /** Gives a run the per-agent rules of its script's {@code inspect} blocks. */
    public void setRules(String client, String traceId, Map<String, InspectRules> rules) {
        state(client, traceId).setRules(rules);
    }

    /** Gives a run the way to read its decision ladders. */
    public void setLadders(String client, String traceId, java.util.function.Supplier<List<Map<String, Object>>> ladders) {
        state(client, traceId).setLadders(ladders);
    }

    public Optional<RunState> find(String client, String traceId) {
        return Optional.ofNullable(runs.get(client + "/" + traceId));
    }

    /** The run a process reported under its own run id (the one in {@code weave}'s run directory). */
    public Optional<RunState> findByRunId(String client, String runId) {
        return runs.values().stream().filter(r -> r.client().equals(client) && runId.equals(r.runId())).findFirst();
    }

    /** The run with this trace id, whichever process reported it. */
    public Optional<RunState> findByTrace(String traceId) {
        return runs.values().stream().filter(r -> r.traceId().equals(traceId)).findFirst();
    }

    /** One short summary per run, newest first by last event. */
    public List<Map<String, Object>> summaries() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RunState r : runs.values()) out.add(r.summary());
        out.sort((x, y) -> String.valueOf(y.get("lastEventAt")).compareTo(String.valueOf(x.get("lastEventAt"))));
        return out;
    }

    /** One view per run, in no particular order. */
    public List<Map<String, Object>> views() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RunState r : runs.values()) out.add(r.view());
        return out;
    }

    private RunState state(String client, String traceId) {
        String key = client + "/" + traceId;
        RunState existing = runs.get(key);
        if (existing != null) return existing;
        RunState created = runs.computeIfAbsent(key, k -> new RunState(client, traceId));
        evict();
        return created;
    }

    /** Most finished runs kept in memory; older ones go (a server still has them in its history). Running, sleeping and paused runs never go. */
    static final int MAX_FINISHED = 50;

    private void evict() {
        List<RunState> done = new ArrayList<>();
        for (RunState r : runs.values()) {
            RunState.Status st = r.status();
            if (st == RunState.Status.FINISHED || st == RunState.Status.FAILED || st == RunState.Status.STOPPED) done.add(r);
        }
        if (done.size() <= MAX_FINISHED) return;
        done.sort((a, b) -> Long.compare(a.lastEventNanos(), b.lastEventNanos()));
        for (int i = 0; i < done.size() - MAX_FINISHED; i++) runs.remove(done.get(i).client() + "/" + done.get(i).traceId());
    }
}
