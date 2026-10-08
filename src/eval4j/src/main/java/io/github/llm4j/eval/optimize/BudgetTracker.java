package io.github.llm4j.eval.optimize;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe counters and deadline enforcing an {@link OptimizerBudget}. */
final class BudgetTracker {

    private final OptimizerBudget budget;
    private final Clock clock;
    private final List<LlmCallCounter> counters;
    private final AtomicLong rollouts = new AtomicLong();
    private final AtomicLong rewriterCalls = new AtomicLong();
    private volatile long startMillis;
    private volatile long priorElapsedMillis;

    BudgetTracker(OptimizerBudget budget, Clock clock, List<LlmCallCounter> counters) {
        this.budget = budget;
        this.clock = clock;
        this.counters = List.copyOf(counters);
        this.startMillis = clock.millis();
    }

    /**
     * Atomically reserves {@code count} rollouts, leaving {@code reserve} untouched for later
     * phases. Returns false, reserving nothing, if that would exceed the rollout cap.
     */
    boolean tryAcquireRollouts(int count, long reserve) {
        long max = budget.maxRollouts();
        if (max <= 0) {
            rollouts.addAndGet(count);
            return true;
        }
        while (true) {
            long current = rollouts.get();
            if (current + count + reserve > max) {
                return false;
            }
            if (rollouts.compareAndSet(current, current + count)) {
                return true;
            }
        }
    }

    void recordRewriterCall() {
        rewriterCalls.incrementAndGet();
    }

    long rollouts() {
        return rollouts.get();
    }

    long rewriterCalls() {
        return rewriterCalls.get();
    }

    long llmCalls() {
        long total = rewriterCalls.get();
        for (LlmCallCounter counter : counters) {
            total += counter.count();
        }
        return total;
    }

    long elapsedMillis() {
        return priorElapsedMillis + (clock.millis() - startMillis);
    }

    /** Which time/call/round cap (if any) has been reached. Rollouts are enforced on acquire. */
    Optional<StopReason> exhausted(long completedRounds) {
        if (budget.maxDuration() != null && elapsedMillis() >= budget.maxDuration().toMillis()) {
            return Optional.of(StopReason.MAX_DURATION);
        }
        if (budget.maxLlmCalls() > 0 && llmCalls() >= budget.maxLlmCalls()) {
            return Optional.of(StopReason.MAX_LLM_CALLS);
        }
        if (budget.maxRounds() > 0 && completedRounds >= budget.maxRounds()) {
            return Optional.of(StopReason.MAX_ROUNDS);
        }
        return Optional.empty();
    }

    /** Restores counters from a checkpoint so a resumed run continues the same budget. */
    void restore(long rolloutsUsed, long rewriterCallsUsed, long elapsedMillisUsed) {
        rollouts.set(rolloutsUsed);
        rewriterCalls.set(rewriterCallsUsed);
        priorElapsedMillis = elapsedMillisUsed;
        startMillis = clock.millis();
    }
}
