package io.github.llm4j.eval.testing;

import io.github.llm4j.LLMClient;
import io.github.llm4j.eval.export.Pricing;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Counts the tokens every model call really uses, prices them, and stops the whole run at a cap.
 * Once stopped, every later call fails at once, so a loop or a retry storm cannot keep spending.
 *
 * <pre>{@code
 * SpendGuard guard = SpendGuard.withPrices(Path.of("prices.properties")).cap(5.00).stage("reasoning", 2.00);
 * LLMClient judge = guard.guard(realJudge, "claude-sonnet-5-5");
 * }</pre>
 *
 * Prices use the {@link Pricing} file format ({@code model = usdPerMillionIn, usdPerMillionOut}). A
 * model with no price costs nothing, which is right for a free-tier key; use {@link
 * #prices(String)} to check.
 */
public final class SpendGuard {

    /** Thrown by every guarded call once the run has been stopped. */
    public static final class SpendStopped extends RuntimeException {
        public SpendStopped(String message) {
            super(message);
        }
    }

    private final Pricing pricing;
    private final AtomicLong micros = new AtomicLong();
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong tokensIn = new AtomicLong();
    private final AtomicLong tokensOut = new AtomicLong();
    private volatile double capUsd = Double.MAX_VALUE;
    private volatile int maxOutputTokens = Integer.MAX_VALUE;
    private volatile String stageName;
    private volatile double stageCeiling = Double.MAX_VALUE;
    private volatile String stopped;

    private SpendGuard(Pricing pricing) {
        this.pricing = pricing;
    }

    public static SpendGuard withPrices(Path pricesFile) {
        return new SpendGuard(Pricing.fromFile(pricesFile));
    }

    public static SpendGuard withPrices(Pricing pricing) {
        return new SpendGuard(pricing);
    }

    /** Stops the run when total spend reaches {@code usd}. */
    public SpendGuard cap(double usd) {
        this.capUsd = usd;
        return this;
    }

    /**
     * Stops the run when a single call returns more output tokens than {@code n} (a runaway
     * generation).
     */
    public SpendGuard maxOutputTokensPerCall(int n) {
        this.maxOutputTokens = n;
        return this;
    }

    /**
     * Gives the next stage its own ceiling: when total spend passes {@code spent now + budgetUsd}
     * the whole run stops, because a stage that costs far more than estimated means the plan's
     * numbers are wrong.
     */
    public SpendGuard stage(String name, double budgetUsd) {
        stageName = name;
        stageCeiling = spentUsd() + budgetUsd;
        return this;
    }

    /** Stops the whole run now: every later guarded call fails. The first reason is kept. */
    public void stop(String reason) {
        if (stopped == null) {
            stopped = reason;
        }
    }

    public boolean prices(String model) {
        return pricing.cost(model, model, 1_000_000, 1_000_000) != null;
    }

    public double spentUsd() {
        return micros.get() / 1_000_000.0;
    }

    public long calls() {
        return calls.get();
    }

    public long tokensIn() {
        return tokensIn.get();
    }

    public long tokensOut() {
        return tokensOut.get();
    }

    public boolean stopped() {
        return stopped != null;
    }

    /** Why the run was stopped, or null. */
    public String reason() {
        return stopped;
    }

    /** Records one call's usage and stops the run if a limit was crossed. */
    public void record(String model, int in, int out) {
        calls.incrementAndGet();
        tokensIn.addAndGet(in);
        tokensOut.addAndGet(out);
        Double cost = pricing.cost(model, model, in, out);
        if (cost != null) {
            micros.addAndGet(Math.round(cost * 1_000_000));
        }
        if (out > maxOutputTokens) {
            stop(
                    "a single call to "
                            + model
                            + " produced "
                            + out
                            + " output tokens (limit "
                            + maxOutputTokens
                            + ")");
        } else if (spentUsd() >= stageCeiling) {
            stop(
                    String.format(
                            Locale.ROOT,
                            "stage '%s' passed its ceiling ($%.2f spent in total)",
                            stageName,
                            spentUsd()));
        } else if (spentUsd() >= capUsd) {
            stop(
                    String.format(
                            Locale.ROOT,
                            "spend $%.2f reached the cap of $%.2f",
                            spentUsd(),
                            capUsd));
        }
    }

    /** A client that refuses once the run is stopped and counts every response it returns. */
    public LLMClient guard(LLMClient delegate, String model) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                check();
                LLMResponse r = delegate.chat(request);
                LLMResponse.TokenUsage u = r.getTokenUsage();
                if (u != null) {
                    record(model, u.getPromptTokens(), u.getCompletionTokens());
                }
                return r;
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                check();
                return delegate.chatStream(request);
            }
        };
    }

    private void check() {
        if (stopped != null) {
            throw new SpendStopped("run stopped: " + stopped);
        }
    }
}
