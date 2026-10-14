package io.github.llm4j.loom.inspect.state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The mutable account {@link RunState} keeps for each tool while a run goes; {@link #snapshot} turns it into a {@link ToolSummary}. Not thread-safe. */
final class ToolTally {

    private final String name;
    private final String kind;
    private long calls;
    private long ran;
    private long successes;
    private long softFailures;
    private final Map<String, Long> failures = new TreeMap<>();
    private final Map<String, ToolSummary.Tally> byAgent = new TreeMap<>();
    private long consecutive;
    private String lastFailureCategory;
    private String lastFailureAt;
    private String lastSuccessAt;
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private final long[] histogram = new long[ToolSummary.BOUNDS.length + 1];

    ToolTally(String name) {
        this(name, "tool");
    }

    ToolTally(String name, String kind) {
        this.name = name;
        this.kind = kind;
    }

    /** A call the runtime never ran. */
    void skipped() {
        calls++;
    }

    /** A call that ran. {@code at} is when it ended (ISO-8601 text); {@code category} is the failure's, ignored for a success. */
    void record(String agent, boolean ok, String category, String at, long millis, boolean soft) {
        calls++;
        ran++;
        String who = agent == null ? "unknown" : agent;
        ToolSummary.Tally before = byAgent.getOrDefault(who, new ToolSummary.Tally(0, 0, 0));
        if (ok) {
            successes++;
            consecutive = 0;
            lastSuccessAt = at;
            byAgent.put(who, new ToolSummary.Tally(before.calls() + 1, before.successes() + 1, before.failures()));
            recent.addLast("");
        } else {
            String c = category == null ? "other" : category;
            failures.merge(c, 1L, Long::sum);
            if (soft) softFailures++;
            consecutive++;
            lastFailureCategory = c;
            lastFailureAt = at;
            byAgent.put(who, new ToolSummary.Tally(before.calls() + 1, before.successes(), before.failures() + 1));
            recent.addLast(c);
        }
        while (recent.size() > ToolHealth.RECENT) recent.removeFirst();
        int bucket = 0;
        while (bucket < ToolSummary.BOUNDS.length && millis > ToolSummary.BOUNDS[bucket]) bucket++;
        histogram[bucket]++;
    }

    ToolSummary snapshot(Thresholds t) {
        List<String> r = new ArrayList<>(recent);
        ToolHealth.Verdict v = ToolHealth.classify(ran, successes, consecutive, r, lastFailureCategory, t);
        List<Long> h = new ArrayList<>();
        for (long x : histogram) h.add(x);
        return new ToolSummary(name, kind, calls, ran, successes, softFailures, failures, byAgent, consecutive, lastFailureCategory, lastFailureAt, lastSuccessAt, r, h, v.state(), v.reason());
    }
}
