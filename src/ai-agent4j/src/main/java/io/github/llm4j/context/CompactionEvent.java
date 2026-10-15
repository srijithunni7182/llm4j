package io.github.llm4j.context;

/**
 * One attempt to compact, as reported to listeners.
 *
 * @param result what the strategy did (for a failure or a stop, an {@code unchanged} result whose note says why)
 * @param reason why it was tried
 * @param iteration the agent iteration (0-based) the attempt was made before
 * @param before the context use that triggered it
 * @param after the estimated use after it ({@code before} when nothing changed)
 * @param failure the strategy's exception, as {@code Type: message}, or null
 */
public record CompactionEvent(
        CompactionResult result,
        CompactionRequest.Reason reason,
        int iteration,
        ContextUsage before,
        ContextUsage after,
        String failure) {}
