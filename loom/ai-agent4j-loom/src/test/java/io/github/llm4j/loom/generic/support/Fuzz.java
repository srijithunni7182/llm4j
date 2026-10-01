package io.github.llm4j.loom.generic.support;

import java.util.Random;
import java.util.function.Consumer;

/**
 * Seeded, repeatable generated-input testing: the same seed gives the same inputs, and a failure says which
 * seed and iteration to replay. {@code -Dloom.fuzz.iterations=N} and {@code -Dloom.fuzz.seed=S} change the run.
 */
public final class Fuzz {

    private Fuzz() {}

    public static int iterations() {
        return Integer.getInteger("loom.fuzz.iterations", 2000);
    }

    public static void run(String name, Consumer<Random> body) {
        long seed = Long.getLong("loom.fuzz.seed", 20261001L);
        int n = iterations();
        for (int i = 0; i < n; i++) {
            Random random = new Random(seed * 31 + i);
            try {
                body.accept(random);
            } catch (Throwable t) {
                throw new AssertionError(name + " failed at iteration " + i + " (replay with -Dloom.fuzz.seed=" + seed
                        + " and iteration " + i + "): " + t.getMessage(), t);
            }
        }
    }
}
