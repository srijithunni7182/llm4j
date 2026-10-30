package io.github.llm4j.loom.eval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Whether a judge may gate. A reference set of outputs is scored independently by two or three
 * people against the same rubric. The people are compared with each other first, by quadratic
 * weighted Cohen's kappa: if they cannot agree, the rubric is under-specified and no judge can be
 * validated against them. Only then is the judge compared with their consensus. Below 0.6 the judge
 * does not gate on that dimension.
 *
 * <p>The thresholds are the guide's starting points; the judge's owner sets the real ones.
 */
public final class JudgeValidation {

    /** What the humans must reach with each other before the judge is looked at. */
    public static final double HUMANS_MUST_AGREE = 0.6;

    /** What the judge must reach with the humans' consensus before it gates. */
    public static final double JUDGE_MUST_AGREE = 0.6;

    private static final int WIDE_MISS = 2;

    private JudgeValidation() {}

    /**
     * One output scored by the people and by the judge.
     *
     * @param id which output
     * @param humans each person's score, two or three
     * @param judge the judge's score, null when it has none
     */
    public record Row(String id, int[] humans, Integer judge) {}

    /** What the validation found. */
    public record Result(
            int outputs,
            double interHumanKappa,
            boolean rubricUnderSpecified,
            Double judgeKappa,
            double meanBias,
            List<String> offByTwoOrMore) {

        /** True only when the people agree and the judge agrees with them. */
        public boolean usableForGating() {
            return !rubricUnderSpecified && judgeKappa != null && judgeKappa >= JUDGE_MUST_AGREE;
        }

        /** The judge's band, or the humans' when the judge was never reached. */
        public String verdict() {
            return band(judgeKappa != null ? judgeKappa : interHumanKappa);
        }

        /** A plain statement of the result and what to do next. */
        public String summary() {
            StringBuilder b = new StringBuilder();
            b.append(String.format(java.util.Locale.ROOT, "inter-human kappa %.2f (%s).", interHumanKappa, band(interHumanKappa)));
            if (rubricUnderSpecified)
                return b.append(" The humans do not agree with each other, so the rubric is under-specified. Rewrite the anchors before scoring anything.").toString();
            b.append(String.format(java.util.Locale.ROOT, " Judge against the human consensus: kappa %.2f (%s), mean bias %+.2f.", judgeKappa, band(judgeKappa), meanBias));
            if (!offByTwoOrMore.isEmpty())
                b.append(" Off by two or more on: ").append(String.join(", ", offByTwoOrMore)).append(".");
            b.append(usableForGating() ? " Usable for gating on this dimension." : " The judge is not usable for gating on this dimension: improve the anchors and validate again.");
            return b.toString();
        }
    }

    /** The reader's band for a kappa. */
    public static String band(double kappa) {
        if (kappa < 0.4) return "poor";
        if (kappa < 0.6) return "moderate";
        if (kappa < 0.8) return "substantial";
        return "strong";
    }

    /**
     * Validates a judge.
     *
     * @throws IllegalArgumentException when there are too few outputs, a row has fewer than two
     *     people, or a row has no judge score: each says which
     */
    public static Result validate(List<Row> rows, int minOutputs) {
        if (rows.size() < minOutputs)
            throw new IllegalArgumentException(
                    "the reference set has "
                            + rows.size()
                            + " outputs; a validation needs at least "
                            + minOutputs
                            + ", spanning the quality range, each scored by two or three people");
        for (Row r : rows) {
            if (r.humans().length < 2)
                throw new IllegalArgumentException(
                        "output " + r.id() + " has " + r.humans().length + " human score(s); at least two are needed to check the people against each other");
            if (r.judge() == null)
                throw new IllegalArgumentException("output " + r.id() + " has no judge score: run the judge over the reference outputs and add its scores");
        }
        double inter = interHuman(rows);
        if (inter < HUMANS_MUST_AGREE) return new Result(rows.size(), inter, true, null, 0, List.of());
        int[] consensus = new int[rows.size()];
        int[] judge = new int[rows.size()];
        double bias = 0;
        List<String> off = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            consensus[i] = consensus(r.humans());
            judge[i] = r.judge();
            bias += judge[i] - consensus[i];
            if (Math.abs(judge[i] - consensus[i]) >= WIDE_MISS) off.add(r.id());
        }
        return new Result(rows.size(), inter, false, kappa(consensus, judge), bias / rows.size(), off);
    }

    /** The mean of every pair of people's kappas over the outputs they both scored. */
    private static double interHuman(List<Row> rows) {
        int people = rows.stream().mapToInt(r -> r.humans().length).min().orElse(2);
        double sum = 0;
        int pairs = 0;
        for (int a = 0; a < people; a++)
            for (int b = a + 1; b < people; b++) {
                int[] x = new int[rows.size()];
                int[] y = new int[rows.size()];
                for (int i = 0; i < rows.size(); i++) {
                    x[i] = rows.get(i).humans()[a];
                    y[i] = rows.get(i).humans()[b];
                }
                sum += kappa(x, y);
                pairs++;
            }
        return sum / pairs;
    }

    /** The people's consensus: the median score, rounded. */
    private static int consensus(int[] humans) {
        int[] sorted = humans.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (int) Math.round((sorted[n / 2 - 1] + sorted[n / 2]) / 2.0);
    }

    /** Quadratic-weighted Cohen's kappa between two raters' scores. */
    public static double kappa(int[] a, int[] b) {
        int n = a.length;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            min = Math.min(min, Math.min(a[i], b[i]));
            max = Math.max(max, Math.max(a[i], b[i]));
        }
        int k = max - min + 1;
        if (k == 1) return 1.0;
        double[][] observed = new double[k][k];
        double[] rowSums = new double[k];
        double[] colSums = new double[k];
        for (int i = 0; i < n; i++) {
            observed[a[i] - min][b[i] - min]++;
            rowSums[a[i] - min]++;
            colSums[b[i] - min]++;
        }
        double disagreeObserved = 0;
        double disagreeExpected = 0;
        for (int i = 0; i < k; i++)
            for (int j = 0; j < k; j++) {
                double w = Math.pow(i - j, 2) / Math.pow(k - 1, 2);
                disagreeObserved += w * observed[i][j] / n;
                disagreeExpected += w * (rowSums[i] / n) * (colSums[j] / n);
            }
        if (disagreeExpected == 0) return disagreeObserved == 0 ? 1.0 : 0.0;
        return 1.0 - disagreeObserved / disagreeExpected;
    }
}
