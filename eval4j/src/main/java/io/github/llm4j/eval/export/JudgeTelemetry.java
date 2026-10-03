package io.github.llm4j.eval.export;

import java.util.concurrent.Callable;

/**
 * Judge activity of the evaluation being computed on this thread, plus the run-wide {@link
 * JudgeStats}. The judge calls report here; the recorder drains the totals when it records the
 * evaluation, so each evaluation carries its own calls, tokens, latency and whether it was served
 * from the judge cache.
 *
 * <p>Calls made on other threads (for example the parallel per-chunk judging in retrieval metrics)
 * count toward the run-wide statistics but cannot be attributed to a single evaluation.
 */
public final class JudgeTelemetry {

    /** What one evaluation consumed. */
    public record Usage(int calls, int cacheHits, int tokensIn, int tokensOut, long latencyMs) {
        public boolean servedFromCache() {
            return calls == 0 && cacheHits > 0;
        }
    }

    private static final class Acc {
        int calls;
        int cacheHits;
        int tokensIn;
        int tokensOut;
        long latencyMs;

        synchronized void call(long ms, int in, int out) {
            calls++;
            tokensIn += in;
            tokensOut += out;
            latencyMs += ms;
        }

        synchronized void hit() {
            cacheHits++;
        }

        synchronized Usage usage() {
            return new Usage(calls, cacheHits, tokensIn, tokensOut, latencyMs);
        }
    }

    /**
     * The evaluation in progress on the capturing thread. Wrap work handed to other threads with
     * {@link #wrap(Callable)} so its judge calls are attributed to the same evaluation.
     */
    public static final class Handle {
        private final Acc acc;

        private Handle(Acc acc) {
            this.acc = acc;
        }

        public <T> Callable<T> wrap(Callable<T> task) {
            return () -> {
                Acc previous = CURRENT.get();
                CURRENT.set(acc);
                try {
                    return task.call();
                } finally {
                    CURRENT.set(previous);
                }
            };
        }
    }

    public static Handle capture() {
        return new Handle(CURRENT.get());
    }

    private static final ThreadLocal<Acc> CURRENT = ThreadLocal.withInitial(Acc::new);

    private JudgeTelemetry() {}

    public static void callMade(
            String judgeId, long latencyMs, int tokensIn, int tokensOut, boolean failure) {
        CURRENT.get().call(latencyMs, tokensIn, tokensOut);
        JudgeStats.call(judgeId, latencyMs, tokensIn, tokensOut, false, failure);
    }

    public static void cacheHit(String judgeId) {
        CURRENT.get().hit();
        JudgeStats.call(judgeId, 0, 0, 0, true, false);
    }

    /** Returns and clears the totals accumulated on this thread since the last drain. */
    public static Usage drain() {
        Usage u = CURRENT.get().usage();
        CURRENT.remove();
        return u;
    }
}
