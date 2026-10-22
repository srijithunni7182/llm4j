package io.github.llm4j.budget;

import io.github.llm4j.classify.Classifier;
import io.github.llm4j.classify.ClassifyRequest;
import io.github.llm4j.classify.ClassifyResult;
import io.github.llm4j.classify.ClassifyUsage;
import io.github.llm4j.model.LLMRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Meters every call of the wrapped {@link Classifier} against a {@link BudgetSet}, as {@link
 * BudgetedLLMClient} does for a chat client:
 *
 * <ol>
 *   <li><b>Preflight.</b> Estimate the call and reserve it from every applicable budget, or refuse
 *       with {@link BudgetExceeded} <em>without calling the classifier</em>. A classifier's output
 *       is a few labels, so a fixed allowance of {@value #OUTPUT_ALLOWANCE} output tokens is
 *       reserved.
 *   <li><b>Settle.</b> Replace the reservation with the usage the backend reported ({@link
 *       ClassifyUsage}), or an estimate, marked as such, when it reported none. A price of the
 *       model (Jev's is on input tokens only) turns tokens into cost when a {@link PriceTable} is
 *       given.
 * </ol>
 *
 * A call that fails is charged its estimated prompt, since a provider may bill a request that
 * failed; a rate limit (429) counts as a call only.
 */
public final class BudgetedClassifier implements Classifier {

    /** The output tokens reserved for a call before it is made. */
    public static final long OUTPUT_ALLOWANCE = 256;

    private final Classifier delegate;
    private final Supplier<BudgetSet> budgets;
    private final TokenEstimator estimator;
    private final PriceTable prices;
    private final String model;
    private final List<BudgetedLLMClient.ChargeListener> chargeListeners =
            new CopyOnWriteArrayList<>();

    private BudgetedClassifier(Builder b) {
        this.delegate = Objects.requireNonNull(b.delegate, "delegate");
        this.budgets = b.budgets != null ? b.budgets : BudgetSet::of;
        this.estimator = b.estimator != null ? b.estimator : CharsPerTokenEstimator.INSTANCE;
        this.prices = b.prices;
        this.model = b.model;
    }

    public static Builder builder(Classifier delegate) {
        return new Builder(delegate);
    }

    public void addChargeListener(BudgetedLLMClient.ChargeListener listener) {
        chargeListeners.add(listener);
    }

    @Override
    public ClassifyResult classify(ClassifyRequest request) {
        BudgetSet set = budgets.get();
        if (set == null) set = BudgetSet.of();
        PriceTable.Price price =
                prices == null || model == null ? null : prices.price(model).orElse(null);
        if (set.hasCostLimit()) {
            if (prices == null) {
                throw new IllegalStateException(
                        "A cost budget needs a price table (per-million input/output prices per model); supply one, or budget in tokens instead.");
            }
            if (price == null) {
                throw new IllegalStateException(
                        "A cost budget is set but model '"
                                + model
                                + "' has no entry in the price table.");
            }
        }
        long prompt = estimator.prompt(asPrompt(request));
        BudgetSet.Lease lease = set.reserve(prompt, OUTPUT_ALLOWANCE, price);
        ClassifyResult result;
        try {
            result = delegate.classify(request);
        } catch (RuntimeException e) {
            if (e instanceof io.github.llm4j.exception.RateLimitException) {
                settle(lease, new Charge(0, 0, 1, BigDecimal.ZERO, false));
            } else {
                settle(lease, new Charge(prompt, 0, 1, cost(price, prompt, 0), true));
            }
            throw e;
        }
        ClassifyUsage usage = result.usage();
        boolean estimated = usage.inputTokens() == 0 && usage.outputTokens() == 0;
        long p = estimated ? prompt : usage.inputTokens();
        long c = usage.outputTokens();
        settle(lease, new Charge(p, c, 1, cost(price, p, c), estimated));
        return result;
    }

    private void settle(BudgetSet.Lease lease, Charge charge) {
        lease.settle(charge);
        for (BudgetedLLMClient.ChargeListener l : chargeListeners) {
            try {
                l.onCharge(model, charge);
            } catch (RuntimeException ignored) {
                // reporting must never break a call
            }
        }
    }

    private static BigDecimal cost(PriceTable.Price price, long p, long c) {
        return price == null ? BigDecimal.ZERO : price.cost(p, c);
    }

    /**
     * The text a classify call sends, as one request, for the estimator: the state and the
     * questions.
     */
    private static LLMRequest asPrompt(ClassifyRequest request) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> field : request.state().entrySet())
            sb.append(field.getKey()).append(' ').append(field.getValue()).append('\n');
        for (io.github.llm4j.classify.Question q : request.questions()) {
            sb.append(q.name()).append(' ').append(q.instructions()).append('\n');
            if (q instanceof io.github.llm4j.classify.Question.Choice c) {
                for (io.github.llm4j.classify.Option o : c.options())
                    sb.append(o.label()).append(' ').append(o.description()).append('\n');
            } else if (q instanceof io.github.llm4j.classify.Question.Score s) {
                sb.append(String.join(" ", s.scale().points())).append('\n');
            }
        }
        return LLMRequest.builder().addUserMessage(sb.toString()).build();
    }

    public static final class Builder {
        private final Classifier delegate;
        private Supplier<BudgetSet> budgets;
        private TokenEstimator estimator;
        private PriceTable prices;
        private String model;

        private Builder(Classifier delegate) {
            this.delegate = delegate;
        }

        /** The budgets that apply, read on every call. */
        public Builder budgets(Supplier<BudgetSet> budgets) {
            this.budgets = budgets;
            return this;
        }

        /** One budget that applies to every call. */
        public Builder budget(Budget budget) {
            BudgetSet set = BudgetSet.of(budget);
            this.budgets = () -> set;
            return this;
        }

        public Builder estimator(TokenEstimator estimator) {
            this.estimator = estimator;
            return this;
        }

        /** Prices per model, needed for a cost budget. */
        public Builder prices(PriceTable prices) {
            this.prices = prices;
            return this;
        }

        /** The model the classifier asks, as the price table names it ({@code jev-latest}). */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public BudgetedClassifier build() {
            return new BudgetedClassifier(this);
        }
    }
}
