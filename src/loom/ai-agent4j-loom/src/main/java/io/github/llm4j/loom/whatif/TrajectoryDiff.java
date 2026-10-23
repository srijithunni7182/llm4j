package io.github.llm4j.loom.whatif;

import io.github.llm4j.loom.execution.DecisionPoint;
import io.github.llm4j.loom.travel.ObserveReplay;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * How two runs of the same script, which agree up to a step, go on differently from it: the first
 * place they part, the steps only one of them took, and the steps both took.
 */
public final class TrajectoryDiff {

    private TrajectoryDiff() {}

    /**
     * @param firstDifference where the two stop agreeing, in words, or null when they do not
     * @param onlyOriginal the steps (as statements) only the original took after the point
     * @param onlyCandidate the steps only the candidate took
     * @param both the steps both took after the point
     */
    public record Diff(
            String firstDifference,
            List<String> onlyOriginal,
            List<String> onlyCandidate,
            List<String> both) {}

    public static Diff between(
            ObserveReplay.Result original,
            ObserveReplay.Result candidate,
            String at,
            Function<String, String> label) {
        List<String> a = from(original.steps(), at);
        List<String> b = from(candidate.steps(), at);
        Set<String> inB = new LinkedHashSet<>(b);
        Set<String> inA = new LinkedHashSet<>(a);
        List<String> onlyA = new ArrayList<>();
        List<String> onlyB = new ArrayList<>();
        List<String> both = new ArrayList<>();
        for (String s : a) (inB.contains(s) ? both : onlyA).add(describe(s, label));
        for (String s : b) if (!inA.contains(s)) onlyB.add(describe(s, label));
        return new Diff(first(original, candidate, at, label), onlyA, onlyB, both);
    }

    private static List<String> from(List<String> steps, String at) {
        int i = steps.indexOf(at);
        return i < 0 ? List.of() : steps.subList(i, steps.size());
    }

    private static String describe(String step, Function<String, String> label) {
        return label.apply(step) + " (" + step + ")";
    }

    /** The first event, from {@code at}, that is not the same in both. */
    private static String first(
            ObserveReplay.Result original,
            ObserveReplay.Result candidate,
            String at,
            Function<String, String> label) {
        List<ObserveReplay.Event> a = eventsFrom(original, at);
        List<ObserveReplay.Event> b = eventsFrom(candidate, at);
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            ObserveReplay.Event x = i < a.size() ? a.get(i) : null;
            ObserveReplay.Event y = i < b.size() ? b.get(i) : null;
            if (x == null)
                return "the original ended; the candidate went on to "
                        + label.apply(y.step())
                        + " ("
                        + y.step()
                        + ")";
            if (y == null)
                return "the candidate "
                        + candidate
                                .status()
                                .replace(
                                        "needs",
                                        "stopped (it needs something the journal does not hold)")
                        + " at "
                        + label.apply(lastStep(candidate))
                        + "; the original went on to "
                        + label.apply(x.step())
                        + " ("
                        + x.step()
                        + ")";
            if (!same(x, y)) return parted(x, y, label);
        }
        return null;
    }

    private static String lastStep(ObserveReplay.Result r) {
        return r.steps().isEmpty() ? "" : r.steps().get(r.steps().size() - 1);
    }

    private static List<ObserveReplay.Event> eventsFrom(ObserveReplay.Result r, String at) {
        List<ObserveReplay.Event> out = new ArrayList<>();
        boolean in = false;
        for (ObserveReplay.Event e : r.timeline()) {
            if (e.decision() == null && e.step().equals(at)) in = true;
            if (in) out.add(e);
        }
        return out;
    }

    private static boolean same(ObserveReplay.Event x, ObserveReplay.Event y) {
        if (!x.step().equals(y.step())) return false;
        if ((x.decision() == null) != (y.decision() == null)) return false;
        if (x.decision() == null) return true;
        DecisionPoint p = x.decision();
        DecisionPoint q = y.decision();
        return p.kind().equals(q.kind())
                && Objects.equals(p.taken(), q.taken())
                && Objects.equals(p.result(), q.result());
    }

    private static String parted(
            ObserveReplay.Event x, ObserveReplay.Event y, Function<String, String> label) {
        if (x.decision() != null && y.decision() != null && x.step().equals(y.step())) {
            DecisionPoint p = x.decision();
            DecisionPoint q = y.decision();
            return p.statement()
                    + " went "
                    + p.taken()
                    + " in the original and "
                    + q.taken()
                    + " in the candidate"
                    + values(p, q);
        }
        return "the original went to "
                + label.apply(x.step())
                + " ("
                + x.step()
                + ") and the candidate to "
                + label.apply(y.step())
                + " ("
                + y.step()
                + ")";
    }

    private static String values(DecisionPoint p, DecisionPoint q) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(p.operands().size(), q.operands().size()); i++) {
            var u = p.operands().get(i);
            var v = q.operands().get(i);
            if (!u.value().equals(v.value()))
                sb.append(sb.length() == 0 ? " (" : ", ")
                        .append(u.expr())
                        .append(" was \"")
                        .append(u.value())
                        .append("\", now \"")
                        .append(v.value())
                        .append("\"");
        }
        return sb.length() == 0 ? "" : sb + ")";
    }
}
