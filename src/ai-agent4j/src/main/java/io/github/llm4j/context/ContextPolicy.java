package io.github.llm4j.context;

import io.github.llm4j.model.Message;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * When to compact and how far, and what must never be touched.
 *
 * @param window the model's window, or null when not known (then nothing compacts automatically)
 * @param triggerAt compact when the next prompt would exceed this fraction of the usable window
 * @param targetAt bring the prompt down to about this fraction
 * @param keepRecent the last this-many messages are never touched
 * @param maxCompactions per run; after that compaction stops
 * @param minGain a result must be at least this fraction smaller to be used
 * @param pinned messages that are never removed or changed, besides those marked with {@link
 *     Messages#pinned}
 * @param onOverflow what to do when the provider rejects a prompt as too long
 */
public record ContextPolicy(
        ContextWindow window,
        double triggerAt,
        double targetAt,
        int keepRecent,
        int maxCompactions,
        double minGain,
        Predicate<Message> pinned,
        OnOverflow onOverflow) {

    public enum OnOverflow {
        /** Compact once, harder, and retry the call once. */
        RETRY_ONCE,
        /** Fail with the provider's error. */
        FAIL
    }

    public ContextPolicy {
        if (!(triggerAt > 0 && triggerAt <= 1))
            throw new IllegalArgumentException("triggerAt must be in (0, 1], got " + triggerAt);
        if (!(targetAt > 0 && targetAt < 1))
            throw new IllegalArgumentException("targetAt must be in (0, 1), got " + targetAt);
        if (targetAt >= triggerAt)
            throw new IllegalArgumentException(
                    "targetAt must be below triggerAt, got " + targetAt + " and " + triggerAt);
        if (keepRecent < 0)
            throw new IllegalArgumentException(
                    "keepRecent must not be negative, got " + keepRecent);
        if (maxCompactions < 0)
            throw new IllegalArgumentException(
                    "maxCompactions must not be negative, got " + maxCompactions);
        if (!(minGain >= 0 && minGain < 1))
            throw new IllegalArgumentException("minGain must be in [0, 1), got " + minGain);
        pinned = Messages.orMarked(pinned);
        if (onOverflow == null) onOverflow = OnOverflow.RETRY_ONCE;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The same policy with this window. */
    public ContextPolicy withWindow(ContextWindow newWindow) {
        return new ContextPolicy(
                newWindow,
                triggerAt,
                targetAt,
                keepRecent,
                maxCompactions,
                minGain,
                pinned,
                onOverflow);
    }

    /** The prompt size that triggers compaction, in tokens; empty without a window. */
    public Optional<Long> triggerTokens() {
        return tokens(triggerAt);
    }

    /** The prompt size compaction aims for, in tokens; empty without a window. */
    public Optional<Long> targetTokens() {
        return tokens(targetAt);
    }

    private Optional<Long> tokens(double fraction) {
        return window == null
                ? Optional.empty()
                : Optional.of((long) Math.floor(window.usableInput() * fraction + 1e-9));
    }

    public static final class Builder {
        private ContextWindow window;
        private double triggerAt = 0.80;
        private double targetAt = 0.50;
        private int keepRecent = 4;
        private int maxCompactions = 8;
        private double minGain = 0.10;
        private Predicate<Message> pinned;
        private OnOverflow onOverflow = OnOverflow.RETRY_ONCE;

        public Builder window(ContextWindow window) {
            this.window = window;
            return this;
        }

        public Builder triggerAt(double triggerAt) {
            this.triggerAt = triggerAt;
            return this;
        }

        public Builder targetAt(double targetAt) {
            this.targetAt = targetAt;
            return this;
        }

        public Builder keepRecent(int keepRecent) {
            this.keepRecent = keepRecent;
            return this;
        }

        public Builder maxCompactions(int maxCompactions) {
            this.maxCompactions = maxCompactions;
            return this;
        }

        public Builder minGain(double minGain) {
            this.minGain = minGain;
            return this;
        }

        public Builder pinned(Predicate<Message> pinned) {
            this.pinned = pinned;
            return this;
        }

        public Builder onOverflow(OnOverflow onOverflow) {
            this.onOverflow = onOverflow;
            return this;
        }

        public ContextPolicy build() {
            return new ContextPolicy(
                    window,
                    triggerAt,
                    targetAt,
                    keepRecent,
                    maxCompactions,
                    minGain,
                    pinned,
                    onOverflow);
        }
    }
}
