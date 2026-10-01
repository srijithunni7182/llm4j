package io.github.llm4j.loom.generic.support;

import java.util.Random;

/**
 * Seeded, repeatable generated-input testing: the same seed gives the same inputs, and a failure says which
 * seed and iteration to replay. {@code -Dloom.fuzz.iterations=N} and {@code -Dloom.fuzz.seed=S} change the run.
 */
public final class Fuzz {

    private Fuzz() {}

    public static int iterations() {
        return Integer.getInteger("loom.fuzz.iterations", 2000);
    }

    /** One generated case; may throw. */
    @FunctionalInterface
    public interface Body {
        void run(Random random) throws Exception;
    }

    public static void run(String name, Body body) {
        long seed = Long.getLong("loom.fuzz.seed", 20261001L);
        int n = iterations();
        for (int i = 0; i < n; i++) {
            Random random = new Random(seed * 31 + i);
            try {
                body.run(random);
            } catch (Throwable t) {
                throw new AssertionError(name + " failed at iteration " + i + " (replay with -Dloom.fuzz.seed=" + seed
                        + " and iteration " + i + "): " + t.getMessage(), t);
            }
        }
    }
}
