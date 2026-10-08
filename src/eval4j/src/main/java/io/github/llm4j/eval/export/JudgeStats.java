package io.github.llm4j.eval.export;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-judge call statistics for the run, shown on the Models page. */
public final class JudgeStats {

    private static final Map<String, Acc> STATS = new ConcurrentHashMap<>();

    private JudgeStats() {}

    /** Records one judge call. */
    public static void call(
            String judgeId,
            long latencyMs,
            int tokensIn,
            int tokensOut,
            boolean cacheHit,
            boolean failure) {
        if (judgeId == null) {
            return;
        }
        Acc a = STATS.computeIfAbsent(judgeId, k -> new Acc());
        synchronized (a) {
            if (cacheHit) {
                a.cacheHits++;
            } else {
                a.calls++;
                a.latencies.add(latencyMs);
            }
            a.tokensIn += tokensIn;
            a.tokensOut += tokensOut;
            if (failure) {
                a.failures++;
            }
        }
    }

    static Snapshot snapshot(String judgeId) {
        Acc a = STATS.get(judgeId);
        if (a == null) {
            return null;
        }
        synchronized (a) {
            List<Long> sorted = new ArrayList<>(a.latencies);
            Collections.sort(sorted);
            Double mean =
                    sorted.isEmpty()
                            ? null
                            : sorted.stream().mapToLong(Long::longValue).average().orElse(0);
            Double p95 =
                    sorted.isEmpty()
                            ? null
                            : (double)
                                    sorted.get(
                                            Math.min(
                                                    sorted.size() - 1,
                                                    (int) Math.ceil(sorted.size() * 0.95) - 1));
            return new Snapshot(
                    a.calls, a.cacheHits, mean, p95, a.tokensIn, a.tokensOut, a.failures);
        }
    }

    public static void reset() {
        STATS.clear();
    }

    private static final class Acc {
        int calls;
        int cacheHits;
        long tokensIn;
        long tokensOut;
        int failures;
        final List<Long> latencies = new ArrayList<>();
    }

    record Snapshot(
            int calls,
            int cacheHits,
            Double meanMs,
            Double p95Ms,
            long tokensIn,
            long tokensOut,
            int failures) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("calls", calls);
            m.put("cacheHits", cacheHits);
            if (meanMs != null) {
                m.put("latencyMeanMs", meanMs);
            }
            if (p95Ms != null) {
                m.put("latencyP95Ms", p95Ms);
            }
            m.put("tokensIn", tokensIn);
            m.put("tokensOut", tokensOut);
            m.put("failures", failures);
            return m;
        }
    }
}
