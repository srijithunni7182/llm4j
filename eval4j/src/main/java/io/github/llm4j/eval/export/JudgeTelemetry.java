package io.github.llm4j.eval.export;

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
    }

    private static final ThreadLocal<Acc> CURRENT = ThreadLocal.withInitial(Acc::new);

    private JudgeTelemetry() {}

    public static void callMade(
            String judgeId, long latencyMs, int tokensIn, int tokensOut, boolean failure) {
        Acc a = CURRENT.get();
        a.calls++;
        a.tokensIn += tokensIn;
        a.tokensOut += tokensOut;
        a.latencyMs += latencyMs;
        JudgeStats.call(judgeId, latencyMs, tokensIn, tokensOut, false, failure);
    }

    public static void cacheHit(String judgeId) {
        CURRENT.get().cacheHits++;
        JudgeStats.call(judgeId, 0, 0, 0, true, false);
    }

    /** Returns and clears the totals accumulated on this thread since the last drain. */
    public static Usage drain() {
        Acc a = CURRENT.get();
        Usage u = new Usage(a.calls, a.cacheHits, a.tokensIn, a.tokensOut, a.latencyMs);
        CURRENT.remove();
        return u;
    }
}
