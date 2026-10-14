package io.github.llm4j.loom.inspect.state;

import io.github.llm4j.loom.inspect.otel.OtlpJson;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The incidents of one run: what is wrong now, and the last few that are over. Opened by detectors, closed when their condition clears
 * or the run ends. Not thread-safe: {@link RunState} calls it under its own lock.
 *
 * <p>Severities are {@code info} (a rate-limit pause: not an error), {@code warn} and {@code high}. A suspect-output incident is
 * labelled as such and is never closed by the console as resolved; it ends only with the run.
 */
final class Incidents {

    static final int KEEP_CLOSED = 20;

    enum Kind { PAUSED, LOOPING, SUSPECT_OUTPUT, FAILED, BUDGET, CONTEXT, TRUNCATED, THRASH, TOOL_RATE, SPIKE, BROKEN, DIVERTED, TRIFECTA, INJECTION, ACCESS }

    private static final List<String> ORDER = List.of("info", "warn", "high");

    static final class Incident {
        final String id;
        final Kind kind;
        String severity;
        final String agent;
        final String step;
        final long sinceNanos;
        long closedNanos;
        String closeReason;
        String title;
        List<Map<String, Object>> evidence;

        Incident(String id, Kind kind, String severity, String agent, String step, long sinceNanos, String title, List<Map<String, Object>> evidence) {
            this.id = id;
            this.kind = kind;
            this.severity = severity;
            this.agent = agent;
            this.step = step;
            this.sinceNanos = sinceNanos;
            this.title = title;
            this.evidence = evidence;
        }
    }

    private final Map<String, Incident> open = new LinkedHashMap<>();
    private final Deque<Incident> closed = new ArrayDeque<>();
    private int counter;

    /** One line of evidence: which rule, what was measured, against what threshold, and the detail that explains it. */
    static Map<String, Object> evidence(String rule, Object measured, Object threshold, String detail) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("rule", rule);
        e.put("measured", measured);
        if (threshold != null) e.put("threshold", threshold);
        if (detail != null) e.put("detail", detail);
        return e;
    }

    /** Opens the incident under {@code key}, or updates it (its severity only ever goes up while it is open). */
    Incident raise(String key, Kind kind, String severity, String agent, String step, long nowNanos, String title, List<Map<String, Object>> evidence) {
        Incident i = open.get(key);
        if (i == null) {
            i = new Incident("inc-" + (++counter), kind, severity, agent, step, nowNanos, title, evidence);
            open.put(key, i);
        } else {
            if (ORDER.indexOf(severity) > ORDER.indexOf(i.severity)) i.severity = severity;
            i.title = title;
            i.evidence = evidence;
        }
        return i;
    }

    boolean isOpen(String key) {
        return open.containsKey(key);
    }

    void close(String key, long nowNanos, String reason) {
        Incident i = open.remove(key);
        if (i == null) return;
        i.closedNanos = nowNanos;
        i.closeReason = reason;
        closed.addFirst(i);
        while (closed.size() > KEEP_CLOSED) closed.removeLast();
    }

    /** Closes every open incident whose key starts with {@code prefix} (empty: all). */
    void closeAll(String prefix, long nowNanos, String reason) {
        for (String key : new ArrayList<>(open.keySet())) if (key.startsWith(prefix)) close(key, nowNanos, reason);
    }

    /** Closes every open incident except those whose key starts with one of the prefixes. */
    void closeAllExcept(long nowNanos, String reason, String... keep) {
        for (String key : new ArrayList<>(open.keySet())) {
            boolean kept = false;
            for (String prefix : keep) if (key.startsWith(prefix)) kept = true;
            if (!kept) close(key, nowNanos, reason);
        }
    }

    int openCount() {
        return open.size();
    }

    /** The worst severity among the open incidents, or null when there are none. */
    String worst() {
        String w = null;
        for (Incident i : open.values()) if (w == null || ORDER.indexOf(i.severity) > ORDER.indexOf(w)) w = i.severity;
        return w;
    }

    /** Open incidents, worst first and then oldest, followed by the recently closed ones. */
    List<Map<String, Object>> view() {
        List<Incident> sorted = new ArrayList<>(open.values());
        sorted.sort((a, b) -> {
            int c = Integer.compare(ORDER.indexOf(b.severity), ORDER.indexOf(a.severity));
            return c != 0 ? c : Long.compare(a.sinceNanos, b.sinceNanos);
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (Incident i : sorted) out.add(show(i, true));
        for (Incident i : closed) out.add(show(i, false));
        return out;
    }

    private static Map<String, Object> show(Incident i, boolean isOpen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id);
        m.put("kind", i.kind.name());
        m.put("severity", i.severity);
        m.put("state", isOpen ? "open" : "closed");
        if (i.agent != null) m.put("agent", i.agent);
        if (i.step != null && !i.step.isEmpty()) m.put("step", i.step);
        m.put("since", OtlpJson.instant(i.sinceNanos).toString());
        if (!isOpen) {
            m.put("closedAt", OtlpJson.instant(i.closedNanos).toString());
            m.put("closeReason", i.closeReason);
        }
        m.put("title", i.title);
        m.put("evidence", i.evidence);
        return m;
    }
}
