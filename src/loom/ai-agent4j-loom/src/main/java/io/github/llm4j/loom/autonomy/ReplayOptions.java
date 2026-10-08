package io.github.llm4j.loom.autonomy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;

/**
 * What a replay was asked to do. Everything here is recorded in the replay's plan so a resumed replay does exactly the same.
 *
 * @param since      only cases decided at or after this
 * @param limit      at most this many cases, after a seeded shuffle
 * @param repeat     how many times each case is replayed, to see how stable the candidate's choice is
 * @param maxTokens  stop cleanly once this many tokens were spent (0: no limit)
 * @param maxCost    stop cleanly once this much was spent (null: no limit)
 * @param policy     a file whose content replaces the file of the same name the candidate's agent reads (for the candidate only)
 */
public record ReplayOptions(Path candidate, String scope, Instant since, int limit, long seed, int repeat, boolean liveReads, boolean allowDrift,
                            boolean noMemory, long maxTokens, BigDecimal maxCost, Path policy) {

    public static final int DEFAULT_LIMIT = 500;

    public ReplayOptions {
        if (limit <= 0) limit = DEFAULT_LIMIT;
        if (repeat <= 0) repeat = 1;
    }
}
