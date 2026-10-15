package io.github.llm4j.context;

import io.github.llm4j.budget.CharsPerTokenEstimator;
import io.github.llm4j.budget.TokenEstimator;
import io.github.llm4j.model.Message;
import java.util.List;
import java.util.Objects;

/**
 * Watches the size of a conversation before each model call and compacts it when the policy says so. One manager serves one run;
 * it is <b>not thread-safe</b> and holds the state of that run (what the provider last reported, how many compactions were made).
 *
 * <p>Guards, so that compaction can never do harm:
 * <ul>
 *   <li>a result that is not at least {@link ContextPolicy#minGain()} smaller is discarded;
 *   <li>after {@link ContextPolicy#maxCompactions()} compactions, or two attempts in a row that gained nothing or failed, compaction
 *       stops for the rest of the run, with one notice;
 *   <li>a strategy that throws never fails the run: the conversation is used as it is.
 * </ul>
 * Without a window (the model's limit is not known) or without a compactor it only reports usage.
 */
public final class ContextManager {

    private static final int MAX_FUTILE_IN_A_ROW = 2;
    /** Estimates are rough, so a prompt is refused before the provider is asked only when it is this much over the window. */
    private static final double REFUSE_ABOVE = 1.25;
    /** After an overflow, aim for this share of the usual target. */
    private static final double OVERFLOW_TARGET_SHARE = 0.5;

    private final ContextPolicy policy;
    private final ContextCompactor compactor;
    private final CompactionRequest sizer;
    private final long overhead;
    private final ContextEvents events;

    private long reportedPrompt;
    private int reportedMessages = -1;
    private int compactions;
    private int futile;
    private boolean stoppedNoticeGiven;

    /**
     * @param policy when and how far to compact; null means the defaults with no window
     * @param compactor the strategy, or null to only report usage
     * @param estimator how to count tokens; null means the default estimator
     * @param overheadTokens tokens a prompt carries besides its messages (tool definitions, for instance)
     * @param events who to tell; null means nobody
     */
    public ContextManager(ContextPolicy policy, ContextCompactor compactor, TokenEstimator estimator, long overheadTokens, ContextEvents events) {
        this.policy = policy != null ? policy : ContextPolicy.builder().build();
        this.compactor = compactor;
        this.sizer = CompactionRequest.builder(1).estimator(estimator != null ? estimator : CharsPerTokenEstimator.INSTANCE).build();
        this.overhead = Math.max(0, overheadTokens);
        this.events = events != null ? events : new ContextEvents() {};
    }

    /**
     * What the provider said the prompt it just received held.
     *
     * @param promptTokens the provider's prompt token count for the call
     * @param messagesSent how many messages that prompt contained
     */
    public void reported(long promptTokens, int messagesSent) {
        if (promptTokens > 0 && messagesSent >= 0) {
            reportedPrompt = promptTokens;
            reportedMessages = messagesSent;
        }
    }

    /** The conversation to send, and the usage that was measured; compacts first when the policy calls for it. */
    public Prepared prepare(List<Message> messages, int iteration) {
        return prepare(messages, iteration, overhead);
    }

    /**
     * Like {@link #prepare(List, int)}, for a prompt whose fixed part changes from call to call (a system prompt and history that are sent
     * along with the messages): {@code overheadTokens} replaces the manager's own overhead for this call.
     */
    public Prepared prepare(List<Message> messages, int iteration, long overheadTokens) {
        long callOverhead = Math.max(0, overheadTokens);
        ContextUsage usage = measure(messages, callOverhead);
        events.onUsage(usage);
        Prepared prepared;
        if (compactor == null || policy.window() == null || usage.used() <= policy.triggerTokens().orElseThrow()) {
            prepared = new Prepared(messages, usage, false);
        } else {
            long target = Math.max(1, policy.targetTokens().orElseThrow() - callOverhead);
            prepared = attempt(messages, iteration, usage, callOverhead, target, CompactionRequest.Reason.THRESHOLD);
        }
        refuseIfHopeless(prepared, callOverhead);
        return prepared;
    }

    /**
     * A provider has rejected the prompt as too long: compact harder than the threshold would (half the usual target, or half the current
     * size when that is smaller or the window is not known) so that the call can be tried again. Subject to the same guards as any compaction. Returns the
     * conversation unchanged when there is no strategy, the policy says to fail, or nothing could be removed; the caller then lets the
     * provider's error stand.
     */
    public Prepared recoverFromOverflow(List<Message> messages, int iteration, long overheadTokens) {
        long callOverhead = Math.max(0, overheadTokens);
        ContextUsage usage = measure(messages, callOverhead);
        if (compactor == null || policy.onOverflow() == ContextPolicy.OnOverflow.FAIL) return new Prepared(messages, usage, false);
        // half of the usual target, but never more than half of what was just rejected: the provider has said our size is too big
        long base = policy.window() != null ? Math.min(policy.targetTokens().orElseThrow(), usage.used()) : usage.used();
        long target = Math.max(1, (long) (base * OVERFLOW_TARGET_SHARE) - callOverhead);
        return attempt(messages, iteration, usage, callOverhead, target, CompactionRequest.Reason.OVERFLOW);
    }

    /** Fails before the provider is asked when even the conversation we would send is well over the window. */
    private void refuseIfHopeless(Prepared prepared, long callOverhead) {
        ContextWindow window = policy.window();
        if (window == null) return;
        long used = prepared.changed() ? callOverhead + sizer.sizeOf(prepared.messages()) : prepared.usage().used();
        if (used <= window.usableInput() * REFUSE_ABOVE) return;
        throw new io.github.llm4j.exception.ContextOverflowException("the prompt (about " + used + " tokens) cannot fit the model's context window ("
                + window.tokens() + " tokens, " + window.usableInput() + " usable for the prompt)"
                + (compactor == null ? " and no compaction strategy is set" : " even after compaction")
                + "; shorten the task, the history or the tool results, or use a model with a larger window");
    }

    private ContextUsage measure(List<Message> messages, long overhead) {
        if (reportedMessages >= 0 && reportedMessages <= messages.size()) {
            long added = sizer.sizeOf(messages.subList(reportedMessages, messages.size()));
            return new ContextUsage(reportedPrompt + added, policy.window(), ContextUsage.Source.REPORTED);
        }
        return new ContextUsage(overhead + sizer.sizeOf(messages), policy.window(), ContextUsage.Source.ESTIMATED);
    }

    private Prepared attempt(List<Message> messages, int iteration, ContextUsage before, long overhead, long target, CompactionRequest.Reason reason) {
        CompactionRequest request = CompactionRequest.builder(target).window(policy.window()).keepRecent(policy.keepRecent()).pinned(policy.pinned())
                .estimator(sizer.estimator()).reason(reason).build();

        if (compactions >= policy.maxCompactions()) return stop(messages, iteration, before, request, "the limit of " + policy.maxCompactions() + " compactions was reached");
        if (futile >= MAX_FUTILE_IN_A_ROW) return stop(messages, iteration, before, request, "no gain " + MAX_FUTILE_IN_A_ROW + " times in a row");

        CompactionResult result;
        String failure = null;
        try {
            result = Objects.requireNonNull(compactor.compact(messages, request), "the compactor returned null");
        } catch (RuntimeException e) {
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
            result = CompactionResult.unchanged(compactor.name(), messages, request, CompactionResult.Outcome.SKIPPED, "the compactor failed");
            futile++;
            events.onCompaction(new CompactionEvent(result, request.reason(), iteration, before, before, failure));
            return new Prepared(messages, before, false);
        }

        // judged on our own measurement, not on the numbers the strategy put in its result
        long sizeBefore = sizer.sizeOf(messages);
        long sizeAfter = sizer.sizeOf(result.messages());
        boolean smallEnough = sizeAfter <= sizeBefore * (1.0 - policy.minGain());
        if (result.outcome() == CompactionResult.Outcome.COMPACTED && smallEnough) {
            compactions++;
            futile = 0;
            reportedMessages = -1; // the provider's count describes a prompt that no longer exists
            ContextUsage after = new ContextUsage(overhead + sizer.sizeOf(result.messages()), policy.window(), ContextUsage.Source.ESTIMATED);
            events.onCompaction(new CompactionEvent(result, request.reason(), iteration, before, after, null));
            return new Prepared(result.messages(), before, true, result.summarizerUsage());
        }

        futile++;
        CompactionResult reported = result.outcome() == CompactionResult.Outcome.COMPACTED
                ? CompactionResult.unchanged(result.strategy(), messages, request, CompactionResult.Outcome.NO_GAIN,
                        "the result was not at least " + Math.round(policy.minGain() * 100) + " % smaller (minGain), so it was not used")
                : result;
        events.onCompaction(new CompactionEvent(reported, request.reason(), iteration, before, before, null));
        return new Prepared(messages, before, false, result.summarizerUsage());
    }

    private Prepared stop(List<Message> messages, int iteration, ContextUsage before, CompactionRequest request, String why) {
        if (!stoppedNoticeGiven) {
            stoppedNoticeGiven = true;
            CompactionResult r = CompactionResult.unchanged(compactor.name(), messages, request, CompactionResult.Outcome.SKIPPED,
                    "compaction stopped for this run: " + why);
            events.onCompaction(new CompactionEvent(r, request.reason(), iteration, before, before, null));
        }
        return new Prepared(messages, before, false);
    }

    /**
     * @param messages the conversation to send (the compacted one when {@code changed})
     * @param usage the context use measured before any compaction
     * @param changed whether {@code messages} is a compaction of what was passed in
     * @param summarizerUsage what the strategy spent on model calls of its own during this call (even if its result was not used), or null
     */
    public record Prepared(List<Message> messages, ContextUsage usage, boolean changed, CompactionUsage summarizerUsage) {
        public Prepared(List<Message> messages, ContextUsage usage, boolean changed) {
            this(messages, usage, changed, null);
        }
    }
}
