package io.github.llm4j.loom.inspect.state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * What a run has shown about security, kept apart from {@link RunState}'s statistics: which legs of the lethal trifecta each agent has actually
 * used, what its tool results looked like (the injection scan's group names, never text), which tools refused access, and what the guards did.
 *
 * <p>It only watches. Nothing here changes what an agent can see, call or send. Not thread-safe: {@link RunState} calls it under its own lock.
 */
final class SecurityState {

    private static final List<String> LEGS = List.of("untrusted", "private", "outward");

    private record Pending(String source, String groups, long count) { }

    private static final class AgentSec {
        final Map<String, Set<String>> used = new TreeMap<>();
        final Map<String, Set<String>> knownBy = new TreeMap<>();
        final Set<String> called = new LinkedHashSet<>();
        boolean assumed;
        boolean assumedTrifectaOpen;
        Pending suspect;

        AgentSec() {
            for (String leg : LEGS) {
                used.put(leg, new LinkedHashSet<>());
                knownBy.put(leg, new LinkedHashSet<>());
            }
        }

        int legs() {
            int n = 0;
            for (String leg : LEGS) if (!used.get(leg).isEmpty()) n++;
            return n;
        }
    }

    private final Incidents incidents;
    private final Function<String, Thresholds> thresholds;
    private final Map<String, AgentSec> agents = new TreeMap<>();
    private final Map<String, Deque<Long>> refusals = new LinkedHashMap<>();
    private final Map<String, Long> refusedBy = new TreeMap<>();
    private final Map<String, Long> groups = new TreeMap<>();
    private final Map<String, Long> sources = new TreeMap<>();
    private long suspect;
    private long refused;
    private long unknownTools;
    private long approvalsRefused;
    private long piiMasked;
    private long piiBlocked;
    private long piiDetected;
    private long bias;

    SecurityState(Incidents incidents, Function<String, Thresholds> thresholds) {
        this.incidents = incidents;
        this.thresholds = thresholds;
    }

    /** A tool call has ended. {@code reach} is null for a trace that does not say; {@code category} is the failure's (lower case) or null. */
    void onTool(String agentName, String tool, String reach, boolean known, String patterns, long patternCount, String category, boolean noResult, String step, long now) {
        if (tool == null) return;
        String agent = agentName == null ? "unknown" : agentName;
        AgentSec a = agents.computeIfAbsent(agent, k -> new AgentSec());
        Thresholds t = thresholds.apply(agentName);
        String where = StepPaths.normalize(step);

        access(a, agent, tool, category, noResult, where, now, t);
        if (noResult) return; // the runtime never ran it: it reached nothing

        if (a.suspect != null) { // the call right after a suspect result is the one that matters
            Pending p = a.suspect;
            a.suspect = null;
            boolean outward = reach != null && List.of(reach.split(",")).contains("outward");
            boolean fresh = !a.called.contains(tool);
            if (outward || fresh) {
                incidents.raise("sec:injection:" + agent + "|" + p.source, Incidents.Kind.INJECTION, "high", agent, where, now,
                        "A result from " + p.source + " read like instructions, and " + agent + "'s next call was " + tool,
                        List.of(Incidents.evidence("injection_scan", p.count, null, injectionDetail(p, agent, t)
                                + "; the next call was " + tool + (outward ? ", which sends or changes something outside the run" : ", a tool " + agent + " had not called before in this run"))));
            }
        }
        a.called.add(tool);

        if (reach != null) {
            for (String leg : reach.split(",")) {
                if (leg.isBlank() || !a.used.containsKey(leg)) continue;
                a.used.get(leg).add(tool);
                if (known) a.knownBy.get(leg).add(tool);
            }
            if (!known) a.assumed = true;
        }
        if (a.legs() == LEGS.size()) trifecta(a, agent, where, now, t);

        if (patterns != null && !patterns.isBlank() && t.injectionScan()) { // a rule that turns the scan off (here, or centrally at a server) silences what a process still sent
            suspect++;
            for (String g : patterns.split(",")) groups.merge(g, 1L, Long::sum);
            sources.merge(tool, 1L, Long::sum);
            Pending p = new Pending(tool, patterns, patternCount);
            a.suspect = p;
            boolean accepted = t.trifectaAccepted() != null;
            incidents.raise("sec:injection:" + agent + "|" + tool, Incidents.Kind.INJECTION, accepted ? "high" : "warn", agent, where, now,
                    "A result from " + tool + " read like instructions to " + agent,
                    List.of(Incidents.evidence("injection_scan", patternCount, null, injectionDetail(p, agent, t))));
        }
    }

    private static String injectionDetail(Pending p, String agent, Thresholds t) {
        return "the result of " + p.source + " matched " + p.count + " time(s) in the groups " + p.groups
                + " (a heuristic: only the group names are kept, never the text)"
                + (t.trifectaAccepted() != null ? "; " + agent + " has an accepted trifecta, so any finding is high" : "");
    }

    private void trifecta(AgentSec a, String agent, String where, long now, Thresholds t) {
        boolean everyLegKnown = true;
        for (String leg : LEGS) if (a.knownBy.get(leg).isEmpty()) everyLegKnown = false;
        String accepted = t.trifectaAccepted();
        String severity = accepted != null ? "info" : everyLegKnown ? "high" : "warn";
        StringBuilder detail = new StringBuilder();
        for (String leg : LEGS) {
            if (detail.length() > 0) detail.append(", ");
            detail.append(String.join("/", a.used.get(leg))).append(" (").append(leg).append(')');
        }
        if (!everyLegKnown) detail.append("; at least one leg rests on a tool whose reach is assumed, not declared (declare it with reach:)");
        if (accepted != null) detail.append("; accepted: ").append(accepted);
        String title = accepted != null ? agent + " used all three legs of the lethal trifecta (accepted)"
                : agent + " read untrusted content, reached private data and sent something out" + (everyLegKnown ? "" : " (assumed)");
        incidents.raise("sec:trifecta:" + agent, Incidents.Kind.TRIFECTA, severity, agent, where, now, title,
                List.of(Incidents.evidence("trifecta", 3L, 3L, detail.toString())));
        a.assumedTrifectaOpen = "warn".equals(severity); // a guess about a tool nobody described: worth a look while it runs, not worth keeping after
    }

    private void access(AgentSec a, String agent, String tool, String category, boolean noResult, String where, long now, Thresholds t) {
        if (category == null) {
            if (!noResult) incidents.close("sec:access:" + tool, now, "the tool answered again");
            return;
        }
        switch (category) {
            case "permission_denied", "authentication" -> {
                refused++;
                refusedBy.merge(category, 1L, Long::sum);
                Deque<Long> times = refusals.computeIfAbsent(tool, k -> new ArrayDeque<>());
                times.addLast(now);
                long span = t.accessDeniedSeconds() * 1_000_000_000L;
                while (!times.isEmpty() && now - times.peekFirst() > span) times.removeFirst();
                if (times.size() >= t.accessDeniedCount()) {
                    incidents.raise("sec:access:" + tool, Incidents.Kind.ACCESS, "warn", agent, where, now, "The tool " + tool + " keeps refusing access",
                            List.of(Incidents.evidence("access_denied", (long) times.size(), (long) t.accessDeniedCount(),
                                    tool + " refused " + agent + " " + times.size() + " times within " + t.accessDeniedSeconds() + " s (" + category + ")")));
                }
            }
            case "unknown_tool" -> {
                unknownTools++;
                incidents.raise("sec:unknown:" + agent + "|" + tool, Incidents.Kind.ACCESS, "info", agent, where, now, agent + " called a tool it does not have: " + tool,
                        List.of(Incidents.evidence("unknown_tool", 1L, null, tool + " is not one of " + agent + "'s tools; the call was blocked. A mistake of the model, or something steering it")));
            }
            case "rejected_by_human", "approval_unavailable" -> approvalsRefused++; // people deciding: counted, never an incident
            default -> { }
        }
    }

    /** The run ended: a trifecta that rested on an assumption is over with it; one that rested on declared tools stays for whoever reviews the run. */
    void onRunEnd(long now) {
        for (Map.Entry<String, AgentSec> e : agents.entrySet()) {
            if (!e.getValue().assumedTrifectaOpen) continue;
            incidents.close("sec:trifecta:" + e.getKey(), now, "the run ended");
            e.getValue().assumedTrifectaOpen = false;
        }
    }

    void onGuard(String event) {
        if (event == null) return;
        switch (event) {
            case "pii_masked" -> piiMasked++;
            case "pii_blocked" -> piiBlocked++;
            case "pii_detected" -> piiDetected++;
            default -> {
                if (event.startsWith("bias")) bias++;
            }
        }
    }

    /** The security section of a run's view. {@code profile} (what each agent can reach, from the static audit) may be empty. */
    Map<String, Object> view(List<Map<String, Object>> profile) {
        Map<String, Map<String, Object>> can = new TreeMap<>();
        for (Map<String, Object> p : profile) can.put(String.valueOf(p.get("agent")), p);
        Set<String> names = new TreeSet<>(agents.keySet());
        names.addAll(can.keySet());
        List<Map<String, Object>> list = new ArrayList<>();
        for (String name : names) {
            AgentSec a = agents.getOrDefault(name, new AgentSec());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agent", name);
            Map<String, Object> usedView = new LinkedHashMap<>();
            for (String leg : LEGS) usedView.put(leg, new ArrayList<>(a.used.get(leg)));
            m.put("used", usedView);
            boolean canAll = false;
            if (can.containsKey(name)) {
                Map<String, Object> c = new LinkedHashMap<>();
                canAll = true;
                for (String leg : LEGS) {
                    Object v = can.get(name).get(leg);
                    List<?> tools = v instanceof List<?> l ? l : List.of();
                    c.put(leg, new ArrayList<>(tools));
                    if (tools.isEmpty()) canAll = false;
                }
                m.put("can", c);
            }
            m.put("assumed", a.assumed);
            String accepted = thresholds.apply(name).trifectaAccepted();
            String verdict;
            if (a.legs() == LEGS.size()) verdict = accepted != null ? "accepted" : "exercised";
            else if (canAll) verdict = "armed";
            else verdict = switch (a.legs()) {
                case 2 -> "two";
                case 1 -> "one";
                default -> "none";
            };
            m.put("verdict", verdict);
            if ("accepted".equals(verdict)) m.put("acceptedReason", accepted);
            list.add(m);
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("agents", list);
        Map<String, Object> inj = new LinkedHashMap<>();
        inj.put("suspect", suspect);
        inj.put("byGroup", new LinkedHashMap<>(groups));
        inj.put("bySource", new LinkedHashMap<>(sources));
        v.put("injection", inj);
        Map<String, Object> acc = new LinkedHashMap<>();
        acc.put("refused", refused);
        acc.put("byCategory", new LinkedHashMap<>(refusedBy));
        acc.put("unknownTools", unknownTools);
        acc.put("approvalsRefused", approvalsRefused);
        v.put("access", acc);
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("pii_masked", piiMasked);
        g.put("pii_blocked", piiBlocked);
        g.put("pii_detected", piiDetected);
        g.put("bias", bias);
        v.put("guards", g);
        return v;
    }

    /** The numbers a server adds up across runs. */
    Map<String, Long> counts() {
        Map<String, Long> c = new LinkedHashMap<>();
        long exercised = 0;
        long accepted = 0;
        for (Map.Entry<String, AgentSec> e : agents.entrySet()) {
            if (e.getValue().legs() != LEGS.size()) continue;
            if (thresholds.apply(e.getKey()).trifectaAccepted() != null) accepted++;
            else exercised++;
        }
        c.put("trifectaExercised", exercised);
        c.put("trifectaAccepted", accepted);
        c.put("injectionSuspect", suspect);
        c.put("accessRefused", refused);
        c.put("unknownTools", unknownTools);
        c.put("approvalsRefused", approvalsRefused);
        c.put("piiMasked", piiMasked);
        c.put("piiBlocked", piiBlocked);
        c.put("piiDetected", piiDetected);
        c.put("bias", bias);
        return c;
    }
}
