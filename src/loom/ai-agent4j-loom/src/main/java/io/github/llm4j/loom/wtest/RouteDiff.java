package io.github.llm4j.loom.wtest;

import java.util.List;

/** Compares the route a test expects with the route that ran, and says where they part. */
final class RouteDiff {

    private RouteDiff() {}

    /**
     * Null when {@code expected} is a subsequence of {@code actual} (or equal to it when {@code
     * exact}); else a short account with the first step that did not happen.
     */
    static String mismatch(List<String> expected, List<String> actual, boolean exact) {
        if (exact) {
            if (expected.equals(actual)) return null;
            int i = 0;
            while (i < expected.size()
                    && i < actual.size()
                    && expected.get(i).equals(actual.get(i))) i++;
            return "route differs at step "
                    + (i + 1)
                    + ": expected "
                    + (i < expected.size() ? "\"" + expected.get(i) + "\"" : "the route to end")
                    + ", got "
                    + (i < actual.size() ? "\"" + actual.get(i) + "\"" : "nothing more")
                    + "\n"
                    + side("expected", expected, i)
                    + side("actual  ", actual, i);
        }
        int at = 0;
        for (int i = 0; i < expected.size(); i++) {
            int found = -1;
            for (int j = at; j < actual.size(); j++)
                if (actual.get(j).equals(expected.get(i))) {
                    found = j;
                    break;
                }
            if (found < 0)
                return "step "
                        + (i + 1)
                        + " of the expected route did not happen: \""
                        + expected.get(i)
                        + "\""
                        + (at > 0 ? " (after \"" + actual.get(at - 1) + "\")" : "")
                        + "\n"
                        + side("expected", expected, i)
                        + side("actual  ", actual, at);
            at = found + 1;
        }
        return null;
    }

    /** One line listing the steps, with a marker where they diverge. */
    private static String side(String name, List<String> steps, int mark) {
        StringBuilder b = new StringBuilder("  " + name + ": ");
        if (steps.isEmpty()) return b.append("(no steps)\n").toString();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) b.append(" > ");
            if (i == mark) b.append(">>");
            b.append(steps.get(i));
        }
        if (mark >= steps.size()) b.append(" >> (end)");
        return b.append('\n').toString();
    }
}
