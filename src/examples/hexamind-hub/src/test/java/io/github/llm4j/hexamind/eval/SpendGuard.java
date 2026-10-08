package io.github.llm4j.hexamind.eval;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Counts the tokens every model call really uses, prices them, and stops the whole run at a cap. Once
 * tripped, every later call fails at once, so a loop or a retry storm cannot keep spending. A call that
 * returns more output tokens than {@code maxOutputTokens} trips it too.
 */
public final class SpendGuard {

    /** Thrown by every guarded call once the run has been stopped. */
    public static final class SpendStopped extends RuntimeException {
        public SpendStopped(String message) {
            super(message);
        }
    }

    private record Rate(double in, double out) {}

    private final Map<String, Rate> rates = new HashMap<>();
    private final double capUsd;
    private final int maxOutputTokens;
    private final AtomicLong micros = new AtomicLong();
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong tokensIn = new AtomicLong();
    private final AtomicLong tokensOut = new AtomicLong();
    private volatile String stopped;
    private volatile String stageName;
    private volatile double stageCeiling = Double.MAX_VALUE;

    public SpendGuard(Path prices, double capUsd, int maxOutputTokens) {
        this.capUsd = capUsd;
        this.maxOutputTokens = maxOutputTokens;
        if (prices != null && Files.exists(prices)) {
            try (Reader r = Files.newBufferedReader(prices)) {
                Properties p = new Properties();
                p.load(r);
                for (String k : p.stringPropertyNames()) {
                    String[] v = p.getProperty(k).split(",");
                    rates.put(
                            k.trim().toLowerCase(Locale.ROOT),
                            new Rate(Double.parseDouble(v[0].trim()), Double.parseDouble(v[1].trim())));
                }
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("cannot read prices " + prices, e);
            }
        }
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

    public String reason() {
        return stopped;
    }

    public boolean prices(String model) {
        return rates.containsKey(model.toLowerCase(Locale.ROOT));
    }

    /** Records one call's usage; stops the run when the cap or the per-call output limit is crossed. */
    public void record(String model, int in, int out) {
        calls.incrementAndGet();
        tokensIn.addAndGet(in);
        tokensOut.addAndGet(out);
        Rate r = rates.get(model.toLowerCase(Locale.ROOT));
        if (r != null) {
            micros.addAndGet(Math.round((in * r.in + out * r.out)));
        }
        if (out > maxOutputTokens) {
            stopped = "a single call to " + model + " produced " + out + " output tokens (limit " + maxOutputTokens + ")";
        } else if (spentUsd() >= stageCeiling) {
            stopped = String.format(Locale.ROOT, "stage '%s' passed its ceiling ($%.2f spent in total)", stageName, spentUsd());
        } else if (spentUsd() >= capUsd) {
            stopped = String.format(Locale.ROOT, "spend $%.2f reached the cap of $%.2f", spentUsd(), capUsd);
        }
    }

    /**
     * Gives the next stage its own ceiling: when total spend passes {@code spent now + budgetUsd} the whole
     * run stops, because a stage that costs well over its estimate means the plan's numbers are wrong.
     */
    public void stage(String name, double budgetUsd) {
        stageName = name;
        stageCeiling = spentUsd() + budgetUsd;
    }

    /** Stops the whole run: every later guarded call fails at once. */
    public void stop(String reason) {
        if (stopped == null) {
            stopped = reason;
        }
    }

    /** A client that refuses once stopped and counts every response. */
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
