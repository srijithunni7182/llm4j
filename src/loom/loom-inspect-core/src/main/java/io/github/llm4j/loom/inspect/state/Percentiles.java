package io.github.llm4j.loom.inspect.state;

import java.util.Arrays;

/**
 * Latency percentiles by nearest rank. Every sample is kept up to {@link #CAPACITY}; after that a deterministic reservoir (Algorithm R with a fixed
 * generator) keeps a uniform sample, so memory is bounded and the same inputs always give the same answers. The count and the maximum stay exact.
 * Not thread-safe: {@link RunState} calls it under its own lock.
 */
final class Percentiles {

    static final int CAPACITY = 10_000;

    private long[] samples = new long[64];
    private int size;
    private long count;
    private long max;
    private long state = 0x9E3779B97F4A7C15L;

    void add(long value) {
        long v = Math.max(0, value);
        count++;
        if (v > max) max = v;
        if (size < CAPACITY) {
            if (size == samples.length) samples = Arrays.copyOf(samples, Math.min(CAPACITY, samples.length * 2));
            samples[size++] = v;
            return;
        }
        long slot = Long.remainderUnsigned(next(), count);
        if (slot < CAPACITY) samples[(int) slot] = v;
    }

    private long next() {
        state ^= state << 13;
        state ^= state >>> 7;
        state ^= state << 17;
        return state;
    }

    long count() {
        return count;
    }

    int retained() {
        return size;
    }

    long max() {
        return max;
    }

    long p50() {
        return percentile(50);
    }

    long p95() {
        return percentile(95);
    }

    /** The smallest sample at or above {@code p} percent of the retained ones; zero when there are none. */
    long percentile(double p) {
        if (size == 0) return 0;
        long[] sorted = Arrays.copyOf(samples, size);
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * size);
        return sorted[Math.min(size, Math.max(1, rank)) - 1];
    }
}
