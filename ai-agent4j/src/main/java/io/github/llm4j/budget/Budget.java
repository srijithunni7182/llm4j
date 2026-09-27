package io.github.llm4j.budget;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An allowance of tokens, LLM calls and (with a {@link PriceTable}) cost, with running totals of what
 * has been spent. Thread-safe. A budget with no limits only counts.
 *
 * <p>Calls don't charge a budget directly: a {@link BudgetedLLMClient} reserves the call's estimate
 * through a {@link BudgetSet} before the call and settles the actual usage after it, so concurrent
 * calls can never jointly overspend by more than their prompt-estimate errors.
 *
 * <pre>{@code
 * Budget run = Budget.builder().name("run").tokens(200_000).calls(150).warnAt(0.8).build();
 * Budget daily = Budget.builder().name("daily").tokens(100_000).window(Window.DAY).build();
 * }</pre>
 *
 * <p>A <b>windowed</b> budget's limits apply per minute, hour or day (aligned to its clock's zone): when
 * the window rolls over, spend starts again from zero. {@link #spent()} is the current window;
 * {@link #lifetimeSpent()} includes every window. Its refusals say when it refills
 * ({@link BudgetExceeded#resetAt()}).
 */
public final class Budget {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final String name;
    private volatile Limits limits;
    private final double warnAt;
    private final long order = SEQUENCE.incrementAndGet(); // total lock order across budgets
    final ReentrantLock lock = new ReentrantLock();
    private final List<BudgetListener> listeners = new CopyOnWriteArrayList<>();
    private final Window window;
    private final Clock clock;

    // guarded by lock
    private long promptTokens;
    private long completionTokens;
    private long calls;
    private BigDecimal cost = BigDecimal.ZERO;
    private boolean estimated;
    private long reservedTokens;
    private long reservedCalls;
    private BigDecimal reservedCost = BigDecimal.ZERO;
    private boolean warned;
    private boolean exhaustedFired;
    // windowed budgets only (guarded by lock)
    private Instant windowStart;
    private Instant windowEnd;
    private long pastPrompt;
    private long pastCompletion;
    private long pastCalls;
    private BigDecimal pastCost = BigDecimal.ZERO;

    private Budget(Builder b) {
        this.name = b.name;
        this.window = b.window;
        this.clock = b.clock != null ? b.clock : Clock.systemUTC();
        if (window != null) {
            Instant now = clock.instant();
            windowStart = window.start(now, clock.getZone());
            windowEnd = window.end(now, clock.getZone());
        }
        this.limits = new Limits(b.tokens, b.calls, b.cost);
        this.warnAt = b.warnAt;
        if (b.listener != null) listeners.add(b.listener);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A budget that never refuses and only counts. */
    public static Budget unlimited(String name) {
        return builder().name(name).build();
    }

    public String name() {
        return name;
    }

    public Limits limits() {
        return limits;
    }

    public double warnAt() {
        return warnAt;
    }

    /** The refill period, or null for a lifetime budget. */
    public Window window() {
        return window;
    }

    /** When the current window ends and the budget refills; null for a lifetime budget. */
    public Instant windowEnd() {
        lock.lock();
        try {
            return windowEndLocked();
        } finally {
            lock.unlock();
        }
    }

    /** Spend across all windows (equal to {@link #spent()} for a lifetime budget). */
    public Spent lifetimeSpent() {
        lock.lock();
        try {
            rollLocked();
            return new Spent(pastPrompt + promptTokens, pastCompletion + completionTokens, pastCalls + calls,
                    pastCost.add(cost), estimated, 0);
        } finally {
            lock.unlock();
        }
    }

    long order() {
        return order;
    }

    public void addListener(BudgetListener listener) {
        listeners.add(listener);
    }

    public void removeListener(BudgetListener listener) {
        listeners.remove(listener);
    }

    public Spent spent() {
        lock.lock();
        try {
            rollLocked();
            long tokens = promptTokens + completionTokens;
            long overdraw = limits.tokens() == null ? 0 : Math.max(0, tokens - limits.tokens());
            return new Spent(promptTokens, completionTokens, calls, cost, estimated, overdraw);
        } finally {
            lock.unlock();
        }
    }

    public Remaining remaining() {
        Spent s = spent();
        return new Remaining(
                limits.tokens() == null ? OptionalLong.empty() : OptionalLong.of(Math.max(0, limits.tokens() - s.tokens())),
                limits.calls() == null ? OptionalLong.empty() : OptionalLong.of(Math.max(0, limits.calls() - s.calls())),
                limits.cost() == null ? Optional.empty() : Optional.of(limits.cost().subtract(s.cost()).max(BigDecimal.ZERO)));
    }

    /** True once any limited dimension has nothing left. */
    public boolean exhausted() {
        Remaining r = remaining();
        return (r.tokens().isPresent() && r.tokens().getAsLong() == 0)
                || (r.calls().isPresent() && r.calls().getAsLong() == 0)
                || (r.cost().isPresent() && r.cost().get().signum() == 0);
    }

    /** True once this budget has refused a call. */
    public boolean refused() {
        lock.lock();
        try {
            rollLocked();
            return exhaustedFired;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Raises the limits by {@code more} (its unset dimensions leave a limit unchanged) — for example after
     * a person approves another allowance. The budget can refuse (and announce it) again afterwards.
     */
    public void raise(Limits more) {
        lock.lock();
        try {
            Limits l = limits;
            limits = new Limits(plus(l.tokens(), more.tokens()), plus(l.calls(), more.calls()),
                    l.cost() == null || more.cost() == null ? l.cost() : l.cost().add(more.cost()));
            exhaustedFired = false;
            warned = false;
        } finally {
            lock.unlock();
        }
    }

    private static Long plus(Long limit, Long more) {
        return limit == null || more == null ? limit : Long.valueOf(limit + more);
    }

    /** Adds spend recorded elsewhere (a resumed run's journaled usage). Never refuses. */
    public void restore(Spent already) {
        restore(already, null);
    }

    /**
     * Adds spend recorded elsewhere at time {@code at}. For a windowed budget, spend from before the
     * current window only counts towards {@link #lifetimeSpent()}. Never refuses.
     */
    public void restore(Spent already, Instant at) {
        List<BudgetEvent> events;
        lock.lock();
        try {
            rollLocked();
            if (window != null && at != null && at.isBefore(windowStart)) {
                pastPrompt += already.promptTokens();
                pastCompletion += already.completionTokens();
                pastCalls += already.calls();
                pastCost = pastCost.add(already.cost());
                estimated |= already.estimated();
                return;
            }
            promptTokens += already.promptTokens();
            completionTokens += already.completionTokens();
            calls += already.calls();
            cost = cost.add(already.cost());
            estimated |= already.estimated();
            events = checkWarning();
        } finally {
            lock.unlock();
        }
        fire(events);
    }

    // ── called by BudgetSet with this budget's lock held ─────────────────────────────────────

    /** Starts a new window if the current one has ended. Reservations in flight carry over. */
    void rollLocked() {
        if (window == null) return;
        Instant now = clock.instant();
        if (now.isBefore(windowEnd)) return;
        pastPrompt += promptTokens;
        pastCompletion += completionTokens;
        pastCalls += calls;
        pastCost = pastCost.add(cost);
        promptTokens = 0;
        completionTokens = 0;
        calls = 0;
        cost = BigDecimal.ZERO;
        warned = false;
        exhaustedFired = false;
        windowStart = window.start(now, clock.getZone());
        windowEnd = window.end(now, clock.getZone());
    }

    Instant windowEndLocked() {
        rollLocked();
        return windowEnd;
    }

    long availableTokens() {
        return limits.tokens() == null ? Long.MAX_VALUE : limits.tokens() - promptTokens - completionTokens - reservedTokens;
    }

    long availableCalls() {
        return limits.calls() == null ? Long.MAX_VALUE : limits.calls() - calls - reservedCalls;
    }

    BigDecimal availableCost() {
        return limits.cost() == null ? null : limits.cost().subtract(cost).subtract(reservedCost);
    }

    void reserveLocked(long tokens, long callCount, BigDecimal money) {
        reservedTokens += tokens;
        reservedCalls += callCount;
        reservedCost = reservedCost.add(money);
    }

    /** Replaces a reservation with the actual charge; returns the events to fire after unlocking. */
    List<BudgetEvent> settleLocked(long tokens, long callCount, BigDecimal money, Charge charge) {
        rollLocked();
        reservedTokens -= tokens;
        reservedCalls -= callCount;
        reservedCost = reservedCost.subtract(money);
        if (charge != null) {
            promptTokens += charge.promptTokens();
            completionTokens += charge.completionTokens();
            calls += charge.calls();
            cost = cost.add(charge.cost());
            estimated |= charge.estimated();
        }
        return checkWarning();
    }

    /** The refusal to throw, and (the first time) the event announcing it. */
    BudgetEvent exhaustedLocked() {
        if (exhaustedFired) return null;
        exhaustedFired = true;
        return new BudgetEvent(BudgetEvent.Kind.EXHAUSTED, name, spentLocked(), limits);
    }

    Spent spentLocked() {
        long tokens = promptTokens + completionTokens;
        long overdraw = limits.tokens() == null ? 0 : Math.max(0, tokens - limits.tokens());
        return new Spent(promptTokens, completionTokens, calls, cost, estimated, overdraw);
    }

    private List<BudgetEvent> checkWarning() {
        List<BudgetEvent> events = new ArrayList<>(1);
        if (warned || !limits.any()) return events;
        double used = 0;
        if (limits.tokens() != null) used = Math.max(used, (double) (promptTokens + completionTokens) / limits.tokens());
        if (limits.calls() != null) used = Math.max(used, (double) calls / limits.calls());
        if (limits.cost() != null && limits.cost().signum() > 0) {
            used = Math.max(used, cost.doubleValue() / limits.cost().doubleValue());
        }
        if (used >= warnAt) {
            warned = true;
            events.add(new BudgetEvent(BudgetEvent.Kind.WARNING, name, spentLocked(), limits));
        }
        return events;
    }

    void fire(List<BudgetEvent> events) {
        for (BudgetEvent e : events) {
            for (BudgetListener l : listeners) {
                try {
                    l.onBudget(e);
                } catch (RuntimeException ignored) {
                    // a listener must never break metering
                }
            }
        }
    }

    @Override
    public String toString() {
        return "Budget{" + name + ": " + limits + (window != null ? " per " + window.name().toLowerCase() : "")
                + ", spent " + spent().tokens() + " tokens}";
    }

    public static final class Builder {
        private String name = "budget";
        private Long tokens;
        private Long calls;
        private BigDecimal cost;
        private double warnAt = 0.8;
        private BudgetListener listener;
        private Window window;
        private Clock clock;

        private Builder() { }

        /** Makes the limits apply per window: spend starts from zero each minute, hour or day. */
        public Builder window(Window window) {
            this.window = window;
            return this;
        }

        /** The clock for windows (and its zone for aligning them). Default: system UTC. */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder tokens(long tokens) {
            if (tokens <= 0) throw new IllegalArgumentException("tokens must be positive");
            this.tokens = tokens;
            return this;
        }

        public Builder calls(long calls) {
            if (calls <= 0) throw new IllegalArgumentException("calls must be positive");
            this.calls = calls;
            return this;
        }

        /** A money limit, e.g. {@code "0.50"} or {@code "$0.50"}. Needs a {@link PriceTable} at call time. */
        public Builder cost(String amount) {
            return cost(new BigDecimal(amount.strip().replaceFirst("^\\$", "")));
        }

        public Builder cost(BigDecimal amount) {
            if (amount.signum() <= 0) throw new IllegalArgumentException("cost must be positive");
            this.cost = amount;
            return this;
        }

        /** Fraction (0–1] at which a WARNING event fires once. Default 0.8. */
        public Builder warnAt(double fraction) {
            if (fraction <= 0 || fraction > 1) throw new IllegalArgumentException("warnAt must be in (0, 1]");
            this.warnAt = fraction;
            return this;
        }

        public Builder listener(BudgetListener listener) {
            this.listener = listener;
            return this;
        }

        public Budget build() {
            return new Budget(this);
        }
    }
}
