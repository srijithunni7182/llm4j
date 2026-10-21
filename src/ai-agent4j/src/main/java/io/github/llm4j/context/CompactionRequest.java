package io.github.llm4j.context;

import io.github.llm4j.budget.CharsPerTokenEstimator;
import io.github.llm4j.budget.TokenEstimator;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.Message;
import java.util.List;
import java.util.function.Predicate;

/**
 * What a {@link ContextCompactor} is asked to do.
 *
 * @param targetTokens bring the conversation down to about this many (estimated) tokens
 * @param window the model's window, or null
 * @param estimator how to count tokens; use {@link #sizeOf(List)} so every strategy counts the same
 *     way
 * @param keepRecent the last this-many messages must be returned unchanged
 * @param pinned messages that must be returned unchanged, besides those marked with {@link
 *     Messages#pinned}
 * @param protectTask whether the first user message (the task of an agent run) must be returned
 *     unchanged; false for a chat history, where it is only the oldest message
 * @param reason why compaction was asked for
 */
public record CompactionRequest(
        long targetTokens,
        ContextWindow window,
        TokenEstimator estimator,
        int keepRecent,
        Predicate<Message> pinned,
        boolean protectTask,
        Reason reason) {

    public enum Reason {
        /** The next prompt would exceed the policy's trigger. */
        THRESHOLD,
        /** The provider rejected a prompt as too long. */
        OVERFLOW,
        /** Asked for by the developer, outside an agent run. */
        MANUAL
    }

    public CompactionRequest {
        if (targetTokens <= 0)
            throw new IllegalArgumentException(
                    "targetTokens must be positive, got " + targetTokens);
        if (keepRecent < 0)
            throw new IllegalArgumentException(
                    "keepRecent must not be negative, got " + keepRecent);
        if (estimator == null) estimator = CharsPerTokenEstimator.INSTANCE;
        pinned = Messages.orMarked(pinned);
        if (reason == null) reason = Reason.MANUAL;
    }

    /** A manual request with the defaults: keep the last 4 messages, nothing pinned. */
    public static CompactionRequest of(long targetTokens) {
        return builder(targetTokens).build();
    }

    public static Builder builder(long targetTokens) {
        return new Builder(targetTokens);
    }

    /** A request taking its protections from a policy. */
    public static CompactionRequest from(ContextPolicy policy, long targetTokens, Reason reason) {
        return builder(targetTokens)
                .window(policy.window())
                .keepRecent(policy.keepRecent())
                .pinned(policy.pinned())
                .reason(reason)
                .build();
    }

    /** The estimated size of these messages as a prompt; zero for none. */
    public long sizeOf(List<Message> messages) {
        if (messages.isEmpty()) return 0;
        return estimator.prompt(LLMRequest.builder().messages(messages).build());
    }

    public static final class Builder {
        private final long targetTokens;
        private ContextWindow window;
        private TokenEstimator estimator;
        private int keepRecent = 4;
        private Predicate<Message> pinned;
        private boolean protectTask = true;
        private Reason reason = Reason.MANUAL;

        private Builder(long targetTokens) {
            this.targetTokens = targetTokens;
        }

        public Builder window(ContextWindow window) {
            this.window = window;
            return this;
        }

        public Builder estimator(TokenEstimator estimator) {
            this.estimator = estimator;
            return this;
        }

        public Builder keepRecent(int keepRecent) {
            this.keepRecent = keepRecent;
            return this;
        }

        public Builder pinned(Predicate<Message> pinned) {
            this.pinned = pinned;
            return this;
        }

        public Builder protectTask(boolean protectTask) {
            this.protectTask = protectTask;
            return this;
        }

        public Builder reason(Reason reason) {
            this.reason = reason;
            return this;
        }

        public CompactionRequest build() {
            return new CompactionRequest(
                    targetTokens, window, estimator, keepRecent, pinned, protectTask, reason);
        }
    }
}
