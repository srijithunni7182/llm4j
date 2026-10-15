package io.github.llm4j.loom.inspect.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;

/**
 * One tool's account of itself: how often it was called, how often it worked, how it failed, who called it, how long it took, and a verdict on its
 * health (see {@link ToolHealth}). It is what a tools-centric view draws, for one run or, merged, for every run a server knows.
 *
 * <p>A <b>call that never ran</b> (the runtime blocked a repeat, the tool was unknown, a person rejected it) counts in {@code calls} but is neither a
 * success nor a failure of the tool.
 *
 * @param kind {@code tool} or {@code task}: a tool and a task of one name are two rows
 * @param calls every call, run or not
 * @param ran calls that actually ran the tool
 * @param successes calls that ran and worked
 * @param softFailures failures where the tool returned normally but its result was an error
 * @param failures failures by category
 * @param byAgent calls, successes and failures by the agent that made them
 * @param consecutiveFailures failures in a row at the end of the run (the longest of the parts, once merged)
 * @param recent the outcomes of the last 10 calls that ran, oldest first: an empty string is a success, anything else the failure category (empty once merged)
 * @param histogram counts of call durations by {@link #BOUNDS} (one more than there are bounds, for the slowest)
 * @param state {@code unused}, {@code healthy}, {@code degraded} or {@code failing}
 * @param reason one line saying why
 */
public record ToolSummary(String name, String kind, long calls, long ran, long successes, long softFailures, Map<String, Long> failures, Map<String, Tally> byAgent,
                          long consecutiveFailures, String lastFailureCategory, String lastFailureAt, String lastSuccessAt, List<String> recent,
                          List<Long> histogram, String state, String reason) {

    /** Upper bounds of the latency buckets, in milliseconds. */
    public static final long[] BOUNDS = {10, 25, 50, 100, 250, 500, 1_000, 2_500, 5_000, 10_000, 30_000};

    /** Calls, successes and failures of one agent with one tool. */
    public record Tally(long calls, long successes, long failures) {
        Tally plus(Tally o) {
            return new Tally(calls + o.calls, successes + o.successes, failures + o.failures);
        }
    }

    public ToolSummary {
        kind = kind == null ? "tool" : kind;
        failures = failures == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(failures));
        byAgent = byAgent == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(byAgent));
        recent = recent == null ? List.of() : List.copyOf(recent);
        histogram = histogram == null ? List.of() : List.copyOf(histogram);
    }

    /** The share of the calls that ran which worked; empty when none ran. */
    public OptionalDouble successRate() {
        return ran == 0 ? OptionalDouble.empty() : OptionalDouble.of(successes / (double) ran);
    }

    /** The share of the last (up to ten) calls that ran which worked; empty when there are none, or once merged. */
    public OptionalDouble recentRate() {
        if (recent.isEmpty()) return OptionalDouble.empty();
        return OptionalDouble.of(recent.stream().filter(String::isEmpty).count() / (double) recent.size());
    }

    public long p50Ms() {
        return percentile(50);
    }

    public long p95Ms() {
        return percentile(95);
    }

    /** The upper bound of the bucket the percentile falls in (twice the last bound for the slowest bucket); zero when nothing was timed. */
    private long percentile(double p) {
        long total = histogram.stream().mapToLong(Long::longValue).sum();
        if (total == 0) return 0;
        long need = (long) Math.ceil(p / 100.0 * total);
        long seen = 0;
        for (int i = 0; i < histogram.size(); i++) {
            seen += histogram.get(i);
            if (seen >= need) return i < BOUNDS.length ? BOUNDS[i] : BOUNDS[BOUNDS.length - 1] * 2;
        }
        return BOUNDS[BOUNDS.length - 1] * 2;
    }

    /** Both accounts of the same tool as one, with the verdict taken again: the worse of the two, or worse still if the counts together say so. */
    public ToolSummary merge(ToolSummary o, Thresholds t) {
        if (!name.equals(o.name) || !kind.equals(o.kind)) throw new IllegalArgumentException("cannot merge " + name + " with " + o.name);
        Map<String, Long> f = new TreeMap<>(failures);
        o.failures.forEach((k, v) -> f.merge(k, v, Long::sum));
        Map<String, Tally> a = new TreeMap<>(byAgent);
        o.byAgent.forEach((k, v) -> a.merge(k, v, Tally::plus));
        List<Long> h = new ArrayList<>();
        int size = Math.max(histogram.size(), o.histogram.size());
        for (int i = 0; i < size; i++) h.add((i < histogram.size() ? histogram.get(i) : 0L) + (i < o.histogram.size() ? o.histogram.get(i) : 0L));
        boolean later = o.lastFailureAt != null && (lastFailureAt == null || o.lastFailureAt.compareTo(lastFailureAt) > 0);
        String lastFailureCategoryMerged = later ? o.lastFailureCategory : lastFailureCategory;
        String lastFailureAtMerged = later ? o.lastFailureAt : lastFailureAt;
        String success = lastSuccessAt == null ? o.lastSuccessAt : o.lastSuccessAt == null ? lastSuccessAt : lastSuccessAt.compareTo(o.lastSuccessAt) >= 0 ? lastSuccessAt : o.lastSuccessAt;
        long ranTotal = ran + o.ran;
        long okTotal = successes + o.successes;
        ToolHealth.Verdict own = ToolHealth.merged(ranTotal, okTotal, t);
        ToolHealth.Verdict floor = ToolHealth.worse(new ToolHealth.Verdict(state, reason), new ToolHealth.Verdict(o.state, o.reason));
        ToolHealth.Verdict v = ToolHealth.rank(own.state()) >= ToolHealth.rank(floor.state()) ? own : floor;
        if (ToolHealth.rank(own.state()) < ToolHealth.rank(floor.state()) && ToolHealth.rank(floor.state()) == 0) v = own;
        return new ToolSummary(name, kind, calls + o.calls, ranTotal, okTotal, softFailures + o.softFailures, f, a, Math.max(consecutiveFailures, o.consecutiveFailures),
                lastFailureCategoryMerged, lastFailureAtMerged, success, List.of(), h, v.state(), v.reason());
    }

    /** The {@code health} part of a tool's row in a run's view, as plain maps and numbers (ready for JSON). */
    public Map<String, Object> toHealthMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("reason", reason);
        recentRate().ifPresent(r -> m.put("recentRate", r));
        m.put("recentCalls", (long) recent.size());
        successRate().ifPresent(r -> m.put("overallRate", r));
        m.put("consecutiveFailures", consecutiveFailures);
        if (lastFailureAt != null) {
            Map<String, Object> lf = new LinkedHashMap<>();
            lf.put("category", lastFailureCategory);
            lf.put("at", lastFailureAt);
            m.put("lastFailure", lf);
        }
        if (lastSuccessAt != null) m.put("lastSuccessAt", lastSuccessAt);
        m.put("failures", new LinkedHashMap<>(failures));
        Map<String, Object> agents = new LinkedHashMap<>();
        byAgent.forEach((agent, t) -> agents.put(agent, Map.of("calls", t.calls(), "successes", t.successes(), "failures", t.failures())));
        m.put("byAgent", agents);
        return m;
    }

    /** The whole summary as a map, for an HTTP answer. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("kind", kind);
        m.put("calls", calls);
        m.put("ran", ran);
        m.put("successes", successes);
        m.put("softFailures", softFailures);
        m.put("p50Ms", p50Ms());
        m.put("p95Ms", p95Ms());
        m.put("histogram", histogram);
        m.put("health", toHealthMap());
        return m;
    }
}
