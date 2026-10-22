package io.github.llm4j.telemetry;

/**
 * A summary of durations in milliseconds. Percentiles use the nearest-rank method. With no samples
 * every number is zero and {@link #isEmpty()} is true.
 *
 * @param count how many durations were recorded (exact)
 * @param min the shortest (exact)
 * @param max the longest (exact)
 * @param mean the average (exact)
 * @param p50 the median
 * @param p90 the 90th percentile
 * @param p95 the 95th percentile
 * @param p99 the 99th percentile
 */
public record LatencyStats(
        long count, long min, long max, double mean, long p50, long p90, long p95, long p99) {

    public static final LatencyStats EMPTY = new LatencyStats(0, 0, 0, 0, 0, 0, 0, 0);

    public boolean isEmpty() {
        return count == 0;
    }
}
