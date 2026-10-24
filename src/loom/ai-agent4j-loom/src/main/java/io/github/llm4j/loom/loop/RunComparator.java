package io.github.llm4j.loom.loop;

import io.github.llm4j.loom.whatif.WhatIf;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * How a run and the candidate's replay of it are compared: the group a run belongs to, the route
 * each took (by what each step does, not by the wording of a condition), where they part, whether a
 * person stopped being asked, and whether the result changed. Pure functions of what the two runs
 * recorded.
 */
final class RunComparator {

    private RunComparator() {}

    /** The classifier labels and human answers a run recorded, and whether it failed. */
    static String stratum(RunRecord run) {
        List<String> parts = new ArrayList<>();
        for (var e : new TreeMap<>(run.journal()).entrySet()) {
            var entry = e.getValue();
            if (entry.kind().equals("classify") && entry.value() instanceof Map<?, ?> m) {
                m.entrySet().stream()
                        .filter(
                                x ->
                                        !String.valueOf(x.getKey())
                                                .matches(
                                                        ".*(_confidence|_probability)|confidence|calibrated|backend|model"))
                        .forEach(x -> parts.add(x.getKey() + "=" + x.getValue()));
            } else if (entry.kind().equals("human")) parts.add("asked a person");
            else if (entry.kind().equals("failed") || e.getKey().endsWith("#failure"))
                parts.add("failed");
        }
        return parts.isEmpty()
                ? "no recorded choices"
                : String.join(", ", parts.stream().sorted().distinct().toList());
    }

    /**
     * A step as it counts for the route: what it does, not the wording of the condition it tests (a
     * reworded condition that sends every run the same way is not a changed route).
     */
    static String shape(String label) {
        if (label.endsWith("?")) return "decision";
        if (label.startsWith("loop until")) return "loop";
        return label;
    }

    /** Steps in {@code a} that {@code b} does not have as many of, in order. */
    static List<String> minus(List<String> a, List<String> b) {
        Map<String, Integer> left = new java.util.HashMap<>();
        b.forEach(x -> left.merge(x, 1, Integer::sum));
        List<String> out = new ArrayList<>();
        for (String x : a) {
            Integer k = left.get(x);
            if (k != null && k > 0) left.put(x, k - 1);
            else out.add(x);
        }
        return out;
    }

    static String firstDifference(List<String> was, List<String> now) {
        int n = Math.min(was.size(), now.size());
        for (int i = 0; i < n; i++)
            if (!was.get(i).equals(now.get(i)))
                return "the run took \""
                        + was.get(i)
                        + "\" at step "
                        + (i + 1)
                        + ", the candidate takes \""
                        + now.get(i)
                        + "\"";
        if (was.size() != now.size())
            return was.size() > now.size()
                    ? "the candidate ends after "
                            + now.size()
                            + " steps; the run went on to \""
                            + was.get(n)
                            + "\""
                    : "the candidate goes on to \"" + now.get(n) + "\" after the run's last step";
        return null;
    }

    static List<String> humanRemoved(List<String> onlyWas, List<String> onlyNow) {
        long before = onlyWas.stream().filter(RunComparator::asksAPerson).count();
        long after = onlyNow.stream().filter(RunComparator::asksAPerson).count();
        return before > after
                ? List.of("a person is no longer asked (" + (before - after) + " step(s))")
                : List.of();
    }

    /** A step label that is a person being asked (the graph's wording, or the statement's). */
    static boolean asksAPerson(String label) {
        return label.startsWith("ask a person") || label.startsWith("human_prompt");
    }

    static boolean resultChanged(WhatIf.Outcome o, String judge, Judges.Judge textJudge)
            throws Exception {
        Object a = o.originalResult();
        Object b = o.candidateResult();
        if (Objects.equals(a, b)) return false;
        boolean typed = a instanceof Map || b instanceof Map || looksTyped(a) || looksTyped(b);
        if (typed) return true;
        if (textJudge != null && a != null && b != null)
            return textJudge.differs(String.valueOf(a), String.valueOf(b));
        return judge.equals("exact") || judge.equals("label");
    }

    static boolean looksTyped(Object o) {
        return o instanceof String s && s.strip().startsWith("{") && s.strip().endsWith("}");
    }
}
