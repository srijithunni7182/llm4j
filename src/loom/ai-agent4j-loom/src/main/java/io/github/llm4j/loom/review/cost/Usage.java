package io.github.llm4j.loom.review.cost;

import io.github.llm4j.budget.PriceTable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a piece of a workflow spends: model calls and tokens, kept per model so that money can be
 * worked out later against a price table. Immutable; the operations return new values.
 */
public final class Usage {

    /** Calls and tokens for one model. */
    public record Spend(long calls, long tokensIn, long tokensOut) {
        Spend plus(Spend o) {
            return new Spend(calls + o.calls, tokensIn + o.tokensIn, tokensOut + o.tokensOut);
        }

        Spend times(long n) {
            return new Spend(calls * n, tokensIn * n, tokensOut * n);
        }

        public long tokens() {
            return tokensIn + tokensOut;
        }
    }

    /** Money for a usage: the sum over priced models, and the models that had no price. */
    public record Money(BigDecimal amount, List<String> unpriced) {
        public boolean complete() {
            return unpriced.isEmpty();
        }
    }

    public static final Usage NONE = new Usage(Map.of());

    private final Map<String, Spend> byModel;

    private Usage(Map<String, Spend> byModel) {
        this.byModel = Collections.unmodifiableMap(new TreeMap<>(byModel));
    }

    /** One call of {@code model}. */
    public static Usage call(String model, long tokensIn, long tokensOut) {
        return calls(model, 1, tokensIn, tokensOut);
    }

    /** {@code n} calls of {@code model} that together read and write these tokens. */
    public static Usage calls(String model, long n, long tokensIn, long tokensOut) {
        return new Usage(Map.of(model, new Spend(n, tokensIn, tokensOut)));
    }

    /** Every figure divided by {@code n}, rounded up (the average of {@code n} alternatives). */
    public Usage dividedBy(long n) {
        if (n <= 1 || byModel.isEmpty()) return this;
        Map<String, Spend> m = new TreeMap<>();
        byModel.forEach(
                (k, v) ->
                        m.put(
                                k,
                                new Spend(
                                        (v.calls + n - 1) / n,
                                        (v.tokensIn + n - 1) / n,
                                        (v.tokensOut + n - 1) / n)));
        return new Usage(m);
    }

    public Usage plus(Usage o) {
        if (o.byModel.isEmpty()) return this;
        if (byModel.isEmpty()) return o;
        Map<String, Spend> m = new TreeMap<>(byModel);
        o.byModel.forEach((k, v) -> m.merge(k, v, Spend::plus));
        return new Usage(m);
    }

    public Usage times(long n) {
        if (n == 1 || byModel.isEmpty()) return this;
        Map<String, Spend> m = new TreeMap<>();
        byModel.forEach((k, v) -> m.put(k, v.times(n)));
        return new Usage(m);
    }

    /** The larger of two by total tokens (used to choose the worst branch). */
    public Usage max(Usage o) {
        return o.tokens() > tokens() ? o : this;
    }

    public long calls() {
        return byModel.values().stream().mapToLong(Spend::calls).sum();
    }

    public long tokensIn() {
        return byModel.values().stream().mapToLong(Spend::tokensIn).sum();
    }

    public long tokensOut() {
        return byModel.values().stream().mapToLong(Spend::tokensOut).sum();
    }

    public long tokens() {
        return tokensIn() + tokensOut();
    }

    public boolean isEmpty() {
        return byModel.isEmpty();
    }

    public Map<String, Spend> byModel() {
        return byModel;
    }

    /**
     * Money against {@code prices}; a model with no price (or no table) is listed, not counted as
     * zero.
     */
    public Money money(PriceTable prices) {
        BigDecimal sum = BigDecimal.ZERO;
        List<String> unpriced = new ArrayList<>();
        for (var e : byModel.entrySet()) {
            var price =
                    prices == null
                            ? java.util.Optional.<PriceTable.Price>empty()
                            : prices.price(e.getKey());
            if (price.isEmpty()) unpriced.add(e.getKey());
            else sum = sum.add(price.get().cost(e.getValue().tokensIn(), e.getValue().tokensOut()));
        }
        return new Money(sum, unpriced);
    }
}
