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
    private volatile Map<String, InspectRules> central = Map.of();
    private volatile PriceBook prices = PriceBook.EMPTY;
    private volatile WindowBook windows = WindowBook.EMPTY;

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

    /** The model each agent of a run uses (from its snapshot), for costing steps from a price book. */
    public void setModels(String client, String traceId, Map<String, String> models) {
        state(client, traceId).setModels(models);
    }

    /**
     * Rules kept at the server: laid over each run's own rules, rule by rule, from now on (for runs already known too). An empty map removes them.
     * Only the thresholds change what is flagged; a variable to watch for no_progress is still read by the process that owns the script.
     */
    public void setCentralRules(Map<String, InspectRules> rules) {
        central = rules == null ? Map.of() : Map.copyOf(rules);
        for (RunState r : runs.values()) r.setCentralRules(central);
    }

    /** Prices kept at the server: a step that reports tokens and no cost is costed from them, from now on. Costs already counted do not change. */
    public void setPrices(PriceBook book) {
        prices = book == null ? PriceBook.EMPTY : book;
        for (RunState r : runs.values()) r.setPrices(prices);
    }

    /** Context windows kept at the server: an agent whose process did not report one gets one by its model, in every run, from now on. */
    public void setWindows(WindowBook book) {
        windows = book == null ? WindowBook.EMPTY : book;
        for (RunState r : runs.values()) r.setWindows(windows);
    }

    /** What the runs known so far say they have: every agent, and each model with the agents that use it. For a page that offers them as choices. */
    public Map<String, Object> catalog() {
        java.util.Set<String> agents = new java.util.TreeSet<>();
        Map<String, java.util.Set<String>> models = new java.util.TreeMap<>();
        for (RunState r : runs.values()) {
            agents.addAll(r.agentNames());
            r.models().forEach((agent, model) -> models.computeIfAbsent(model, k -> new java.util.TreeSet<>()).add(agent));
        }
        List<Map<String, Object>> modelRows = new ArrayList<>();
        models.forEach((m, a) -> modelRows.add(Map.of("model", m, "agents", List.copyOf(a))));
        return Map.of("agents", List.copyOf(agents), "models", modelRows);
    }

    /**
     * Every tool of every run (or of one client's runs), merged into one account per tool and listed with the failing ones first, then the degraded, then
     * by the number of calls: the data of a tools-centric view. The thresholds are the defaults with the central {@code *} block laid over them.
     */
    public List<ToolSummary> toolRollup(String client) {
        InspectRules all = central.get("*");
        Thresholds t = all == null ? Thresholds.defaults() : all.over(Thresholds.defaults());
        Map<String, ToolSummary> merged = new java.util.TreeMap<>();
        for (RunState r : runs.values()) {
            if (client != null && !client.equals(r.client())) continue;
            for (ToolSummary s : r.toolSummaries()) merged.merge(s.kind() + ":" + s.name(), s, (a, b) -> a.merge(b, t));
        }
        List<ToolSummary> out = new ArrayList<>(merged.values());
        out.sort((a, b) -> {
            int c = Integer.compare(ToolHealth.rank(b.state()), ToolHealth.rank(a.state()));
            if (c != 0) return c;
            c = Long.compare(b.calls(), a.calls());
            return c != 0 ? c : a.name().compareTo(b.name());
        });
        return out;
    }

    /** What each agent of a run can reach (from the static audit of its script), so the security view can show it next to what was done. */
    public void setSecurityProfile(String client, String traceId, java.util.function.Supplier<List<Map<String, Object>>> profile) {
        state(client, traceId).setSecurityProfile(profile);
    }

    /**
     * The security counts of every run, added up (or of one client's): agents that used all three legs of the lethal trifecta (and those accepted),
     * suspect results, refused access, unknown tools, refused approvals, and what the guards did.
     */
    public Map<String, Long> securityRollup(String client) {
        Map<String, Long> total = new java.util.LinkedHashMap<>();
        for (RunState r : runs.values()) {
            if (client != null && !client.equals(r.client())) continue;
            r.securityCounts().forEach((k, v) -> total.merge(k, v, Long::sum));
        }
        return total;
    }

    /**
     * The tools and tasks of every run (or one client's) for a fleet view: each row is a {@link ToolSummary} as a map, plus the agents that use it and, when it has
     * ever failed, the run it last failed in ({@code lastRun}: client, trace id, time, category); and the number of rows in each health state. Failing first.
     */
    public Map<String, Object> toolRows(String client) {
        List<ToolSummary> rollup = toolRollup(client);
        Map<String, Map<String, Object>> last = new java.util.HashMap<>();
        for (RunState r : runs.values()) {
            if (client != null && !client.equals(r.client())) continue;
            for (ToolSummary s : r.toolSummaries()) {
                if (s.lastFailureAt() == null) continue;
                String key = s.kind() + ":" + s.name();
                Map<String, Object> best = last.get(key);
                if (best == null || s.lastFailureAt().compareTo(String.valueOf(best.get("at"))) > 0) {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("client", r.client());
                    m.put("traceId", r.traceId());
                    m.put("at", s.lastFailureAt());
                    m.put("category", s.lastFailureCategory());
                    last.put(key, m);
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (String state : List.of("failing", "degraded", "healthy", "unused")) counts.put(state, 0L);
        for (ToolSummary s : rollup) {
            Map<String, Object> m = new java.util.LinkedHashMap<>(s.toMap());
            m.put("agents", new ArrayList<>(s.byAgent().keySet()));
            Map<String, Object> lastRun = last.get(s.kind() + ":" + s.name());
            if (lastRun != null) m.put("lastRun", lastRun);
            rows.add(m);
            counts.merge(s.state(), 1L, Long::sum);
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("rows", rows);
        out.put("counts", counts);
        return out;
    }

    /** The agent rows listed on a security page, and the open incidents shown on it. */
    public static final int SECURITY_AGENTS = 200;
    public static final int SECURITY_INCIDENTS = 100;

    /**
     * The security picture across runs (or one client's): the added-up counts, the agents whose verdict matters (exercised, accepted or armed), and the open
     * security incidents, newest run first and bounded.
     */
    public Map<String, Object> securityOverview(String client) {
        List<RunState> newestFirst = new ArrayList<>();
        for (RunState r : runs.values()) if (client == null || client.equals(r.client())) newestFirst.add(r);
        newestFirst.sort((a, b) -> {
            java.time.Instant x = a.lastEventAt();
            java.time.Instant y = b.lastEventAt();
            if (x == null || y == null) return x == y ? 0 : x == null ? 1 : -1;
            return y.compareTo(x);
        });
        List<Map<String, Object>> agents = new ArrayList<>();
        List<Map<String, Object>> incidents = new ArrayList<>();
        for (RunState r : newestFirst) {
            for (Map<String, Object> a : r.securityAgents()) if (agents.size() < SECURITY_AGENTS) agents.add(a);
            for (Map<String, Object> i : r.securityIncidents()) if (incidents.size() < SECURITY_INCIDENTS) incidents.add(i);
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("counts", securityRollup(client));
        out.put("agents", agents);
        out.put("incidents", incidents);
        return out;
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
        RunState created = runs.computeIfAbsent(key, k -> {
            RunState r = new RunState(client, traceId);
            r.setCentralRules(central);
            r.setPrices(prices);
            r.setWindows(windows);
            return r;
        });
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
