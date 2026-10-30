package io.github.llm4j.loom.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Routing as a confusion matrix: for each case tagged {@code route:<Agent>}, the agent that should
 * handle it against every agent that took part in the run. Mis-routing is asymmetric (sending an
 * information-gathering request to an action-taking agent skips the gathering that would justify
 * the action), so the cells a team names as dangerous gate on their own, not through an average.
 */
public final class RoutingMatrix {

    private static final String ROUTE = "route:";

    private final Map<String, Map<String, Integer>> cells;
    private final int cases;

    private RoutingMatrix(Map<String, Map<String, Integer>> cells, int cases) {
        this.cells = cells;
        this.cases = cases;
    }

    /** The matrix of the cases that name an expected agent. */
    public static RoutingMatrix of(List<ScenarioResult> results) {
        Map<String, Map<String, Integer>> cells = new TreeMap<>();
        int cases = 0;
        for (ScenarioResult r : results) {
            String expected = r.tags().stream().filter(t -> t != null && t.startsWith(ROUTE)).map(t -> t.substring(ROUTE.length())).findFirst().orElse(null);
            if (expected == null) continue;
            cases++;
            Map<String, Integer> row = cells.computeIfAbsent(expected, k -> new TreeMap<>());
            for (AgentFacts a : r.agents()) row.merge(a.agent(), 1, Integer::sum);
        }
        return new RoutingMatrix(cells, cases);
    }

    /** Cases that named an expected agent. */
    public int cases() {
        return cases;
    }

    /** How many cases expected {@code expected} and ran {@code actual}. */
    public int count(String expected, String actual) {
        return cells.getOrDefault(expected, Map.of()).getOrDefault(actual, 0);
    }

    /** Reads {@code Expected>Actual}. */
    public static List<String> parseDanger(String cell) {
        String[] parts = cell.split(">", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank())
            throw new IllegalArgumentException("a dangerous cell is written Expected>Actual (for example Gatherer>Actor), not " + cell);
        return List.of(parts[0].strip(), parts[1].strip());
    }

    /** The named dangerous cells that have any case in them, one line each. */
    public List<String> dangerous(List<String> named) {
        List<String> out = new ArrayList<>();
        for (String cell : named) {
            List<String> p = parseDanger(cell);
            int n = count(p.get(0), p.get(1));
            if (n > 0)
                out.add("routing: " + n + " case" + (n == 1 ? "" : "s") + " expected " + p.get(0) + " and ran " + p.get(1) + " (a dangerous cell).");
        }
        return out;
    }

    /** The matrix as report lines; empty when no case named an expected agent. */
    public List<String> lines(List<String> dangerous) {
        List<String> l = new ArrayList<>();
        if (cases == 0) return l;
        TreeSet<String> actual = new TreeSet<>();
        cells.values().forEach(r -> actual.addAll(r.keySet()));
        l.add("routing (expected agent against the agents that ran), " + cases + " cases:");
        for (String expected : cells.keySet()) {
            StringBuilder b = new StringBuilder("  expected " + expected + ":");
            for (String a : actual) {
                int n = count(expected, a);
                if (n == 0) continue;
                boolean danger = dangerous.stream().anyMatch(d -> parseDanger(d).equals(List.of(expected, a)));
                b.append(" ").append(a).append("=").append(n).append(danger ? " (DANGEROUS)" : "");
            }
            l.add(b.toString());
        }
        return l;
    }
}
