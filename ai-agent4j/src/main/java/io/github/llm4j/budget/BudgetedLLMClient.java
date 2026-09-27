package io.github.llm4j.budget;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Spliterators;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Meters every call of the wrapped client against a {@link BudgetSet}:
 * <ol>
 *   <li><b>Preflight.</b> Estimate the call and reserve it from every applicable budget, or refuse with
 *       {@link BudgetExceeded} <em>without calling the model</em>.</li>
 *   <li><b>Cap.</b> Lower the request's {@code maxTokens} to what the budgets can still afford, so the
 *       answer itself can't break them.</li>
 *   <li><b>Settle.</b> Replace the reservation with the provider's reported usage — or an estimate,
 *       marked as such, when the provider reports none.</li>
 * </ol>
 * The budgets are read from a supplier on every call, so the caller can change which budgets apply
 * (run, agent, step…) without rebuilding clients.
 *
 * <p>Responses carry {@value #ESTIMATED} (true when usage was estimated) and {@value #COST} (when a
 * price is known) in their metadata.
 */
public final class BudgetedLLMClient implements LLMClient {

    public static final String ESTIMATED = "llm4j.usage.estimated";
    public static final String COST = "llm4j.cost";

    /** Told about every settled call — for spend reports. */
    @FunctionalInterface
    public interface ChargeListener {
        void onCharge(String model, Charge charge);
    }

    private final LLMClient delegate;
    private final Supplier<BudgetSet> budgets;
    private final TokenEstimator estimator;
    private final PriceTable prices;
    private final String model;
    private final Integer perCallCap;
    private final List<ChargeListener> chargeListeners = new CopyOnWriteArrayList<>();

    private BudgetedLLMClient(Builder b) {
        this.delegate = Objects.requireNonNull(b.delegate, "delegate");
        this.budgets = b.budgets != null ? b.budgets : BudgetSet::of;
        this.estimator = b.estimator != null ? b.estimator : CharsPerTokenEstimator.INSTANCE;
        this.prices = b.prices;
        this.model = b.model;
        this.perCallCap = b.perCallCap;
    }

    public static Builder builder(LLMClient delegate) {
        return new Builder(delegate);
    }

    public LLMClient delegate() {
        return delegate;
    }

    public void addChargeListener(ChargeListener listener) {
        chargeListeners.add(listener);
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        Call call = begin(request);
        LLMResponse response;
        try {
            response = delegate.chat(call.request);
        } catch (RuntimeException e) {
            call.failed();
            throw e;
        }
        return call.settle(response, response.getContent());
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        Call call = begin(request);
        Stream<LLMResponse> source;
        try {
            source = delegate.chatStream(call.request);
        } catch (RuntimeException e) {
            call.failed();
            throw e;
        }
        Iterator<LLMResponse> it = source.iterator();
        StringBuilder text = new StringBuilder();
        LLMResponse[] last = new LLMResponse[1];
        Iterator<LLMResponse> metered = new Iterator<>() {
            @Override
            public boolean hasNext() {
                boolean more;
                try {
                    more = it.hasNext();
                } catch (RuntimeException e) {
                    call.failed();
                    throw e;
                }
                if (!more) call.settle(last[0], text.toString());
                return more;
            }

            @Override
            public LLMResponse next() {
                LLMResponse chunk = it.next();
                if (chunk.getContent() != null) text.append(chunk.getContent());
                last[0] = chunk;
                return chunk;
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(metered, 0), false)
                .onClose(() -> {
                    call.settle(last[0], text.toString());
                    source.close();
                });
    }

    // ── one call ─────────────────────────────────────────────────────────────────────────────

    private Call begin(LLMRequest request) {
        BudgetSet set = budgets.get();
        if (set == null) set = BudgetSet.of();
        String modelId = request.getModel() != null ? request.getModel() : model;
        PriceTable.Price price = prices == null ? null : prices.price(modelId).orElse(null);
        if (set.hasCostLimit()) {
            if (prices == null) {
                throw new IllegalStateException("A cost budget needs a price table (per-million input/output prices "
                        + "per model); supply one, or budget in tokens instead.");
            }
            if (price == null) {
                throw new IllegalStateException("A cost budget is set but model '" + modelId
                        + "' has no entry in the price table.");
            }
        }
        long prompt = estimator.prompt(request);
        Long wanted = min(request.getMaxTokens(), perCallCap);
        BudgetSet.Lease lease = set.reserve(prompt, wanted, price);
        LLMRequest sent = request;
        if (lease.output() != null) {
            int cap = (int) Math.min(Integer.MAX_VALUE, lease.output());
            if (request.getMaxTokens() == null || request.getMaxTokens() != cap) sent = withMaxTokens(request, cap);
        }
        return new Call(sent, lease, prompt, price, modelId);
    }

    private final class Call {
        final LLMRequest request;
        final BudgetSet.Lease lease;
        final long prompt;
        final PriceTable.Price price;
        final String modelId;
        private boolean settled;

        Call(LLMRequest request, BudgetSet.Lease lease, long prompt, PriceTable.Price price, String modelId) {
            this.request = request;
            this.lease = lease;
            this.prompt = prompt;
            this.price = price;
            this.modelId = modelId;
        }

        /** A sent request that failed: providers may bill it, so charge its prompt and one call, estimated. */
        void failed() {
            charge(new Charge(prompt, 0, 1, cost(prompt, 0), true));
        }

        LLMResponse settle(LLMResponse response, String content) {
            LLMResponse.TokenUsage usage = response == null ? null : response.getTokenUsage();
            boolean estimated = usage == null;
            long p = estimated ? prompt : usage.getPromptTokens();
            long c = estimated ? estimator.completion(content) : usage.getCompletionTokens();
            BigDecimal money = cost(p, c);
            charge(new Charge(p, c, 1, money, estimated));
            if (response == null || (!estimated && price == null)) return response;
            Map<String, Object> meta = new HashMap<>(response.getMetadata() == null ? Map.of() : response.getMetadata());
            if (estimated) meta.put(ESTIMATED, true);
            if (price != null) meta.put(COST, money);
            return LLMResponse.builder()
                    .content(response.getContent())
                    .model(response.getModel())
                    .tokenUsage(estimated ? new LLMResponse.TokenUsage((int) p, (int) c, (int) (p + c)) : usage)
                    .finishReason(response.getFinishReason())
                    .metadata(meta)
                    .build();
        }

        private synchronized void charge(Charge charge) {
            if (settled) return;
            settled = true;
            lease.settle(charge);
            for (ChargeListener l : chargeListeners) {
                try {
                    l.onCharge(modelId, charge);
                } catch (RuntimeException ignored) {
                    // reporting must never break a call
                }
            }
        }

        private BigDecimal cost(long p, long c) {
            return price == null ? BigDecimal.ZERO : price.cost(p, c);
        }
    }

    private static Long min(Integer a, Integer b) {
        if (a == null) return b == null ? null : (long) b;
        if (b == null) return (long) a;
        return (long) Math.min(a, b);
    }

    static LLMRequest withMaxTokens(LLMRequest r, int maxTokens) {
        LLMRequest.Builder b = LLMRequest.builder()
                .messages(r.getMessages())
                .model(r.getModel())
                .temperature(r.getTemperature())
                .maxTokens(maxTokens)
                .topP(r.getTopP())
                .stopSequences(r.getStopSequences())
                .complexityHint(r.getComplexityHint());
        if (r.getAdditionalParameters() != null && !r.getAdditionalParameters().isEmpty()) {
            b.additionalParameters(r.getAdditionalParameters());
        }
        return b.build();
    }

    public static final class Builder {
        private final LLMClient delegate;
        private Supplier<BudgetSet> budgets;
        private TokenEstimator estimator;
        private PriceTable prices;
        private String model;
        private Integer perCallCap;

        private Builder(LLMClient delegate) {
            this.delegate = delegate;
        }

        /** The budgets that apply, read on every call. */
        public Builder budgets(Supplier<BudgetSet> budgets) {
            this.budgets = budgets;
            return this;
        }

        public Builder budget(Budget budget) {
            BudgetSet set = BudgetSet.of(budget);
            this.budgets = () -> set;
            return this;
        }

        public Builder estimator(TokenEstimator estimator) {
            this.estimator = estimator;
            return this;
        }

        public Builder prices(PriceTable prices) {
            this.prices = prices;
            return this;
        }

        /** The model id used for pricing when requests don't name one. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Caps every answer's output tokens. */
        public Builder perCallCap(Integer maxTokens) {
            if (maxTokens != null && maxTokens <= 0) throw new IllegalArgumentException("perCallCap must be positive");
            this.perCallCap = maxTokens;
            return this;
        }

        public BudgetedLLMClient build() {
            return new BudgetedLLMClient(this);
        }
    }
}
