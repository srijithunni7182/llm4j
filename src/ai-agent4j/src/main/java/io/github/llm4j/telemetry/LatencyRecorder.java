package io.github.llm4j.telemetry;

import java.util.Arrays;
import java.util.Random;

/**
 * Collects durations and summarises them. Thread-safe. Up to {@code capacity} samples (10,000 by default) are kept and the percentiles are exact;
 * past that a deterministic reservoir (Algorithm R, seeded) keeps a uniform sample, so memory stays bounded while the count, minimum, maximum and
 * mean stay exact.
 */
public final class LatencyRecorder {

    public static final int DEFAULT_CAPACITY = 10_000;

    private final long[] sample;
    private final Random random;
    private int retained;
    private long count;
    private long min = Long.MAX_VALUE;
    private long max;
    private double sum;

    public LatencyRecorder() {
        this(DEFAULT_CAPACITY, 42L);
    }

    public LatencyRecorder(int capacity, long seed) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        this.sample = new long[capacity];
        this.random = new Random(seed);
    }

    public synchronized void add(long millis) {
        if (millis < 0) throw new IllegalArgumentException("a duration cannot be negative, got " + millis);
        count++;
        sum += millis;
        min = Math.min(min, millis);
        max = Math.max(max, millis);
        if (retained < sample.length) {
            sample[retained++] = millis;
        } else {
            long j = (long) (random.nextDouble() * count); // uniform in [0, count)
            if (j < sample.length) sample[(int) j] = millis;
        }
    }

    /** How many samples are held; never more than the capacity. */
    public synchronized int retained() {
        return retained;
    }

    public synchronized LatencyStats snapshot() {
        if (count == 0) return LatencyStats.EMPTY;
        long[] sorted = Arrays.copyOf(sample, retained);
        Arrays.sort(sorted);
        return new LatencyStats(count, min, max, sum / count, rank(sorted, 50), rank(sorted, 90), rank(sorted, 95), rank(sorted, 99));
    }

    /** Nearest rank: the value at position ceil(p/100 * n), counting from 1. */
    private static long rank(long[] sorted, int percentile) {
        int n = sorted.length;
        int position = (int) Math.ceil(percentile / 100.0 * n);
        return sorted[Math.max(1, Math.min(n, position)) - 1];
    }
}
