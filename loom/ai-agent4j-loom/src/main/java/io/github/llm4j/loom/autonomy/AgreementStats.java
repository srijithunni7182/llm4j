package io.github.llm4j.loom.autonomy;

import io.github.llm4j.loom.ast.DecisionDef;
import java.util.List;

/**
 * The one place agreement is computed. {@code status}, the promotion check and the replay report all call it, so a number a person reads
 * is the number a decision was made on.
 *
 * <p>Agreement is judged by the lower end of the 95% Wilson score interval, not by the raw rate: 20 cases out of 20 is not 100% evidence.
 */
public final class AgreementStats {

    /** z for a two-sided 95% interval. */
    static final double Z = 1.959964;

    private AgreementStats() { }

    /**
     * One case that counts as evidence: what the agent proposed and what the person decided.
     *
     * @param malformed the proposal was not one of the choices (it was treated as escalate)
     * @param reversed  the verdict was undone later (an outcome of {@code reversed} on a verdict the agent made)
     */
    public record Sample(String proposal, String decision, boolean malformed, boolean reversed) {
        public Sample(String proposal, String decision) {
            this(proposal, decision, false, false);
        }
    }

    /** What a window of evidence adds up to. */
    public record Figures(int cases, int matches, double rate, double lowerBound, int dangerous, double dangerousRate, double coverage,
                          int malformed, int reversals) {
        public static final Figures EMPTY = new Figures(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** The lower end of the 95% Wilson interval of {@code k} agreements in {@code n} cases; 0 when there are no cases. */
    public static double wilsonLower(int k, int n) {
        if (n <= 0) return 0;
        double p = (double) k / n;
        double z2 = Z * Z;
        double centre = p + z2 / (2.0 * n);
        double margin = Z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n));
        return Math.max(0, (centre - margin) / (1 + z2 / n));
    }

    /** The figures of these cases against the declared dangerous mistakes. */
    public static Figures of(List<Sample> samples, List<DecisionDef.Mistake> dangerous) {
        int n = samples.size();
        if (n == 0) return Figures.EMPTY;
        int matches = 0, danger = 0, bad = 0, reversals = 0, proposing = 0;
        for (Sample s : samples) {
            if (s.proposal().equals(s.decision())) matches++;
            if (dangerous.stream().anyMatch(m -> m.proposed().equals(s.proposal()) && m.decided().equals(s.decision()))) danger++;
            if (s.malformed()) bad++;
            if (s.reversed()) reversals++;
            if (!"escalate".equals(s.proposal())) proposing++;
        }
        return new Figures(n, matches, (double) matches / n, wilsonLower(matches, n), danger, (double) danger / n, (double) proposing / n, bad, reversals);
    }
}
