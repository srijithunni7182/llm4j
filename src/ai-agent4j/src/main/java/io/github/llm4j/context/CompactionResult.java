package io.github.llm4j.context;

import io.github.llm4j.model.Message;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * What a compaction did.
 *
 * @param messages the conversation to use from now on (an immutable copy)
 * @param strategy the strategy's name
 * @param outcome whether anything was gained
 * @param messagesBefore message count in
 * @param messagesAfter message count out
 * @param tokensBefore estimated size in
 * @param tokensAfter estimated size out
 * @param droppedMessages input messages that are no longer in the output as they were (removed or
 *     replaced)
 * @param summarizerUsage the strategy's own model calls, or null
 * @param note a short reason for a skip or a fallback, or null
 */
public record CompactionResult(
        List<Message> messages,
        String strategy,
        Outcome outcome,
        int messagesBefore,
        int messagesAfter,
        long tokensBefore,
        long tokensAfter,
        int droppedMessages,
        CompactionUsage summarizerUsage,
        String note) {

    public enum Outcome {
        /** The output is smaller than the input. */
        COMPACTED,
        /** The strategy could not make it smaller; the input is returned. */
        NO_GAIN,
        /** Nothing was needed (already within the target) or compaction was off. */
        SKIPPED
    }

    public CompactionResult {
        messages = List.copyOf(messages);
        if (strategy == null || strategy.isBlank())
            throw new IllegalArgumentException("strategy must be named");
        if (outcome == null) throw new IllegalArgumentException("outcome must be given");
    }

    /**
     * The result of turning {@code before} into {@code after}. When {@code after} is not smaller
     * than {@code before} the original is returned with outcome {@link Outcome#NO_GAIN}, so a
     * strategy can never make a prompt bigger.
     */
    public static CompactionResult of(
            String strategy, List<Message> before, List<Message> after, CompactionRequest request) {
        long sizeBefore = request.sizeOf(before);
        long sizeAfter = request.sizeOf(after);
        if (sizeAfter >= sizeBefore) {
            return new CompactionResult(
                    before,
                    strategy,
                    Outcome.NO_GAIN,
                    before.size(),
                    before.size(),
                    sizeBefore,
                    sizeBefore,
                    0,
                    null,
                    null);
        }
        Set<Message> kept = Collections.newSetFromMap(new IdentityHashMap<>());
        kept.addAll(after);
        int dropped = 0;
        for (Message m : before) if (!kept.contains(m)) dropped++;
        return new CompactionResult(
                after,
                strategy,
                Outcome.COMPACTED,
                before.size(),
                after.size(),
                sizeBefore,
                sizeAfter,
                dropped,
                null,
                null);
    }

    public static CompactionResult of(
            ContextCompactor strategy,
            List<Message> before,
            List<Message> after,
            CompactionRequest request) {
        return of(strategy.name(), before, after, request);
    }

    /** The input, unchanged, with the reason. */
    public static CompactionResult unchanged(
            String strategy,
            List<Message> messages,
            CompactionRequest request,
            Outcome outcome,
            String note) {
        long size = request.sizeOf(messages);
        return new CompactionResult(
                messages,
                strategy,
                outcome,
                messages.size(),
                messages.size(),
                size,
                size,
                0,
                null,
                note);
    }

    public CompactionResult withUsage(CompactionUsage usage) {
        return new CompactionResult(
                messages,
                strategy,
                outcome,
                messagesBefore,
                messagesAfter,
                tokensBefore,
                tokensAfter,
                droppedMessages,
                usage,
                note);
    }

    public CompactionResult withNote(String newNote) {
        return new CompactionResult(
                messages,
                strategy,
                outcome,
                messagesBefore,
                messagesAfter,
                tokensBefore,
                tokensAfter,
                droppedMessages,
                summarizerUsage,
                newNote);
    }
}
