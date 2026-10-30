package io.github.llm4j.loom.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Whether a judge that compares two answers favours the position they are shown in. Each pair is
 * judged twice, the second time with the answers swapped; a judge without position bias picks the
 * same answer both times, so its picks are opposite letters (A then B, or B then A) or a tie both
 * times. Picks that follow the position instead are the bias. Fewer pairs than the minimum cannot
 * clear a judge.
 */
public final class PositionBias {

    /** A verdict that changes with the order in more than this share of pairs is a bias. */
    static final double MAX_INCONSISTENT = 0.2;

    /** A first (or second) position chosen more than this share of the time is a bias. */
    static final double MAX_POSITION_SHARE = 0.65;

    private PositionBias() {}

    /**
     * One pair judged both ways.
     *
     * @param id the pair, for the report
     * @param first the pick with the answers in the original order: {@code A}, {@code B} or {@code tie}
     * @param swapped the pick with them swapped, in the letters of that second presentation
     */
    public record Pair(String id, String first, String swapped) {}

    /**
     * What the check found.
     *
     * @param pairs how many pairs were judged both ways
     * @param inconsistent how many were not given the same verdict both ways
     * @param firstShare the share of non-tie picks that took the first position
     * @param notes the findings, empty for a judge without the bias
     * @param biased whether the judge may not gate on this evidence
     */
    public record Result(int pairs, int inconsistent, double firstShare, List<String> notes, boolean biased) {}

    /**
     * Checks the pairs.
     *
     * @param minPairs the fewest pairs the check may rest on, at least 1
     * @throws IllegalArgumentException when a pick is not A, B or tie (the pair is named) or {@code
     *     minPairs} is below 1; an empty list is never cleared
     */
    public static Result check(List<Pair> pairs, int minPairs) {
        if (minPairs < 1) throw new IllegalArgumentException("the fewest pairs a position check may rest on is at least 1, got " + minPairs);
        int inconsistent = 0;
        int firstPicks = 0;
        int picks = 0;
        for (Pair p : pairs) {
            String first = pick(p, p.first());
            String swapped = pick(p, p.swapped());
            boolean consistent = first.equals("tie") ? swapped.equals("tie") : !swapped.equals("tie") && !swapped.equals(first);
            if (!consistent) inconsistent++;
            for (String s : List.of(first, swapped))
                if (!s.equals("tie")) {
                    picks++;
                    if (s.equals("a")) firstPicks++;
                }
        }
        double share = picks == 0 ? 0.5 : (double) firstPicks / picks;
        List<String> notes = new ArrayList<>();
        boolean biased = false;
        if (pairs.size() < minPairs) {
            notes.add("Position bias could not be cleared: " + pairs.size() + " pairs were judged both ways and at least " + minPairs + " are needed.");
            biased = true;
        } else {
            double rate = (double) inconsistent / pairs.size();
            if (rate > MAX_INCONSISTENT) {
                notes.add(
                        String.format(
                                Locale.ROOT,
                                "Position bias: the judge changes its verdict when the order is swapped in %d of %d pairs (%.0f%%).",
                                inconsistent,
                                pairs.size(),
                                100 * rate));
                biased = true;
            }
            if (share > MAX_POSITION_SHARE || share < 1 - MAX_POSITION_SHARE) {
                notes.add(
                        String.format(
                                Locale.ROOT,
                                "It favours the %s position (%.0f%% of its picks). Judge each pair both ways and keep only verdicts that agree.",
                                share > 0.5 ? "first" : "second",
                                100 * (share > 0.5 ? share : 1 - share)));
                biased = true;
            }
        }
        return new Result(pairs.size(), inconsistent, share, List.copyOf(notes), biased);
    }

    private static String pick(Pair p, String value) {
        String v = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!List.of("a", "b", "tie").contains(v))
            throw new IllegalArgumentException("pair " + p.id() + ": a pick is A, B or tie, got '" + value + "'");
        return v;
    }
}
