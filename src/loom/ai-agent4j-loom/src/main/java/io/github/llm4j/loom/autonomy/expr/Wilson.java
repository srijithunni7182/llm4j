package io.github.llm4j.loom.autonomy.expr;

/**
 * The 95% Wilson score interval of {@code k} successes in {@code n} trials: 20 out of 20 is not
 * treated as 100%.
 */
public final class Wilson {

    private static final double Z = 1.959964;

    private Wilson() {}

    public static double lower(int k, int n) {
        return n == 0 ? 0 : Math.max(0, center(k, n) - half(k, n));
    }

    public static double upper(int k, int n) {
        return n == 0 ? 0 : Math.min(1, center(k, n) + half(k, n));
    }

    private static double center(int k, int n) {
        double p = (double) k / n;
        return (p + Z * Z / (2 * n)) / (1 + Z * Z / n);
    }

    private static double half(int k, int n) {
        double p = (double) k / n;
        return Z * Math.sqrt((p * (1 - p) + Z * Z / (4.0 * n)) / n) / (1 + Z * Z / n);
    }
}
