package io.github.llm4j.agent.memory;

import io.github.llm4j.budget.CharsPerTokenEstimator;
import io.github.llm4j.budget.TokenEstimator;
import io.github.llm4j.context.CompactionRequest;
import io.github.llm4j.context.CompactionResult;
import io.github.llm4j.context.ContextCompactor;
import io.github.llm4j.model.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages conversation history for the agent: a sliding window of messages, optionally also a window in <b>tokens</b> with a
 * {@link ContextCompactor} to use when it is exceeded, and optional persistence. Not thread-safe.
 *
 * <pre>{@code
 * ConversationHistory history = ConversationHistory.builder()
 *         .maxTokens(8_000)                                   // what the agent is shown of the past
 *         .compaction(ContextCompactors.summarize(client))    // summarise the oldest part instead of just forgetting it
 *         .build();
 * }</pre>
 *
 * <p>The window applies to the view the agent is shown. A store, if there is one, keeps the full record unless {@code persistCompacted(true)}
 * is asked for.
 */
public class ConversationHistory {

    private static final Logger logger = LoggerFactory.getLogger(ConversationHistory.class);

    private final String sessionId;
    private final ConversationStore store;
    private final int maxHistorySize;
    private final long maxTokens; // 0: no window in tokens
    private final ContextCompactor compactor;
    private final int keepRecent;
    private final Predicate<Message> pinned;
    private final double compactTo;
    private final boolean persistCompacted;
    private final TokenEstimator estimator;
    private final Consumer<CompactionResult> onCompaction;

    // Local cache (or transient state if no store)
    private final List<Message> messages = new ArrayList<>();

    public ConversationHistory(int maxHistorySize) {
        this(UUID.randomUUID().toString(), new InMemoryConversationStore(), maxHistorySize);
    }

    public ConversationHistory(String sessionId, ConversationStore store, int maxHistorySize) {
        this(legacy(sessionId, store, maxHistorySize));
    }

    /** The constructors that came first accept any size, as they always did; only the builder insists on a positive one. */
    private static Builder legacy(String sessionId, ConversationStore store, int maxHistorySize) {
        Builder b = builder().sessionId(sessionId).store(store);
        b.maxMessages = maxHistorySize;
        return b;
    }

    private ConversationHistory(Builder b) {
        this.sessionId = b.sessionId != null ? b.sessionId : UUID.randomUUID().toString();
        this.store = b.store;
        this.maxHistorySize = b.maxMessages;
        this.maxTokens = b.maxTokens;
        this.compactor = b.compactor;
        this.keepRecent = b.keepRecent;
        this.pinned = io.github.llm4j.context.Messages.orMarked(b.pinned);
        this.compactTo = b.compactTo;
        this.persistCompacted = b.persistCompacted;
        this.estimator = b.estimator != null ? b.estimator : CharsPerTokenEstimator.INSTANCE;
        this.onCompaction = b.onCompaction;

        // Load initial history if available
        if (store != null) {
            this.messages.addAll(store.loadHistory(sessionId, maxHistorySize));
            if (maxTokens > 0) enforceTokenWindow();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public void addUserMessage(String content) {
        Message msg = Message.user(content);
        addMessage(msg);
    }

    public void addAssistantMessage(String content) {
        Message msg = Message.assistant(content);
        addMessage(msg);
    }

    public void addMessage(Message message) {
        messages.add(message);
        if (store != null) {
            store.saveMessage(sessionId, message);
        }
        trimHistory();
    }

    public void clear() {
        messages.clear();
        if (store != null) {
            store.clear(sessionId);
        }
    }

    public String getFormattedHistory() {
        if (messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            sb.append(msg.getRole().getValue()).append(": ").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }

    private void trimHistory() {
        // Keep only the last maxHistorySize messages
        while (messages.size() > maxHistorySize) {
            messages.remove(0);
        }
        if (maxTokens > 0) enforceTokenWindow();
    }

    /** Brings the view within the window in tokens: first with the compactor, if there is one, then by letting the oldest messages go. */
    private void enforceTokenWindow() {
        CompactionRequest sizer = CompactionRequest.builder(1).estimator(estimator).build();
        long size = sizer.sizeOf(messages);
        if (size <= maxTokens) return;
        if (compactor != null) compact(size, sizer);
        size = sizer.sizeOf(messages);
        while (size > maxTokens && messages.size() > 1) { // the latest message is always kept
            messages.remove(0);
            size = sizer.sizeOf(messages);
        }
    }

    private void compact(long size, CompactionRequest sizer) {
        long target = Math.max(1, (long) (maxTokens * compactTo));
        CompactionRequest request = CompactionRequest.builder(target).estimator(estimator).keepRecent(keepRecent).pinned(pinned)
                .protectTask(false) // in a chat the first message is only the oldest
                .reason(CompactionRequest.Reason.THRESHOLD).build();
        try {
            CompactionResult result = compactor.compact(List.copyOf(messages), request);
            tell(result);
            if (result.outcome() == CompactionResult.Outcome.COMPACTED && sizer.sizeOf(result.messages()) < size) {
                messages.clear();
                messages.addAll(result.messages());
                if (persistCompacted && store != null) {
                    store.clear(sessionId);
                    for (Message m : messages) store.saveMessage(sessionId, m);
                }
            }
        } catch (RuntimeException e) {
            // a compactor must never make adding a message fail: the window slides instead
            logger.warn("Compacting the conversation history failed; the oldest messages are dropped instead: {}", e.toString());
        }
    }

    private void tell(CompactionResult result) {
        if (onCompaction == null) return;
        try {
            onCompaction.accept(result);
        } catch (RuntimeException e) {
            logger.warn("A compaction listener of the conversation history failed: {}", e.toString());
        }
    }

    public List<Message> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    public String getSessionId() {
        return sessionId;
    }

    /** Settings for a history; see {@link ConversationHistory}. */
    public static final class Builder {
        private String sessionId;
        private ConversationStore store;
        private int maxMessages = 100;
        private long maxTokens;
        private ContextCompactor compactor;
        private int keepRecent = 4;
        private Predicate<Message> pinned;
        private double compactTo = 0.6;
        private boolean persistCompacted;
        private TokenEstimator estimator;
        private Consumer<CompactionResult> onCompaction;

        private Builder() {}

        /** The session in the store; a random one if not given. */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /** Where messages are kept between runs; none by default (the history lives in memory only). */
        public Builder store(ConversationStore store) {
            this.store = store;
            return this;
        }

        /** The most messages shown, whatever their size (default 100). */
        public Builder maxMessages(int maxMessages) {
            if (maxMessages <= 0) throw new IllegalArgumentException("maxMessages must be positive, got " + maxMessages);
            this.maxMessages = maxMessages;
            return this;
        }

        /** The most (estimated) tokens shown. The latest message is always kept, even if it alone is larger. */
        public Builder maxTokens(long maxTokens) {
            if (maxTokens <= 0) throw new IllegalArgumentException("maxTokens must be positive, got " + maxTokens);
            this.maxTokens = maxTokens;
            return this;
        }

        /** What to do when the window in tokens is exceeded; without one the oldest messages simply go. Needs {@link #maxTokens}. */
        public Builder compaction(ContextCompactor compactor) {
            this.compactor = compactor;
            return this;
        }

        /** The last this-many messages are never touched by compaction (default 4). */
        public Builder keepRecent(int keepRecent) {
            if (keepRecent < 0) throw new IllegalArgumentException("keepRecent must not be negative, got " + keepRecent);
            this.keepRecent = keepRecent;
            return this;
        }

        /** Messages the compactor must never remove or change; see {@code Messages.pinned}. */
        public Builder pinned(Predicate<Message> pinned) {
            this.pinned = pinned;
            return this;
        }

        /** Compact down to this share of {@link #maxTokens} (default 0.6), so that it is not done on every message. */
        public Builder compactTo(double fraction) {
            if (!(fraction > 0 && fraction < 1)) throw new IllegalArgumentException("compactTo must be between 0 and 1, exclusive, got " + fraction);
            this.compactTo = fraction;
            return this;
        }

        /**
         * Rewrite the store with the compacted history. Off by default: the store then keeps every message, and only the view the agent is
         * shown is compacted (again, when the history is loaded). Needs a {@link #store}.
         */
        public Builder persistCompacted(boolean persist) {
            this.persistCompacted = persist;
            return this;
        }

        /** How to count tokens; the default estimator if not given. */
        public Builder estimator(TokenEstimator estimator) {
            this.estimator = estimator;
            return this;
        }

        /** Told of every attempt to compact, including the model usage of a summary. */
        public Builder onCompaction(Consumer<CompactionResult> listener) {
            this.onCompaction = listener;
            return this;
        }

        public ConversationHistory build() {
            if (compactor != null && maxTokens <= 0) throw new IllegalStateException("a compaction strategy needs maxTokens: the window it protects");
            if (persistCompacted && store == null) throw new IllegalStateException("persistCompacted needs a store to rewrite");
            return new ConversationHistory(this);
        }
    }
}
