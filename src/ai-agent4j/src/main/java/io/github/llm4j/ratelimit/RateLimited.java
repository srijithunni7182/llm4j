package io.github.llm4j.ratelimit;

import io.github.llm4j.agent.AgentInterrupt;
import io.github.llm4j.budget.BudgetExceeded;
import java.time.Instant;

/**
 * An agent hit a limit that lifts at a known time — a provider rate limit or quota, or a budget window
 * — too far away to wait for inline. It is an {@link AgentInterrupt}: never retried, never turned into
 * a partial answer. A harness catches it to pause the run and resume it at {@link #resetAt()}.
 */
public final class RateLimited extends AgentInterrupt {

    public enum Reason {
        PROVIDER_LIMIT,
        BUDGET_WINDOW
    }

    private final RateLimitInfo info;
    private final Reason reason;
    private final BudgetExceeded budgetExceeded;

    public RateLimited(RateLimitInfo info, Reason reason) {
        this(info, reason, null);
    }

    public RateLimited(RateLimitInfo info, Reason reason, BudgetExceeded budgetExceeded) {
        super(info.describe() + "; resets at " + info.resetAt());
        this.info = info;
        this.reason = reason;
        this.budgetExceeded = budgetExceeded;
    }

    /** A budget window ran out; the limit lifts when the window rolls over. */
    public static RateLimited of(BudgetExceeded exceeded) {
        RateLimitInfo.Scope scope = switch (exceeded.dimension()) {
            case CALLS -> RateLimitInfo.Scope.REQUESTS;
            case TOKENS -> RateLimitInfo.Scope.TOKENS;
            case COST -> RateLimitInfo.Scope.UNKNOWN;
        };
        Instant reset = exceeded.resetAt().orElseThrow(() -> new IllegalArgumentException("budget has no window"));
        return new RateLimited(new RateLimitInfo(reset, scope, "budget:" + exceeded.budget(), null, null, null,
                false, exceeded.getMessage()), Reason.BUDGET_WINDOW, exceeded);
    }

    public RateLimitInfo info() {
        return info;
    }

    public Instant resetAt() {
        return info.resetAt();
    }

    public Reason reason() {
        return reason;
    }

    /** The budget refusal behind a {@link Reason#BUDGET_WINDOW} interrupt, else null. */
    public BudgetExceeded budgetExceeded() {
        return budgetExceeded;
    }
}
