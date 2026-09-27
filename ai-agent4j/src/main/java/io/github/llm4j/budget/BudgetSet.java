package io.github.llm4j.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The budgets that apply to one LLM call — for example run + agent + step. A call is allowed only if
 * every member can afford it, and it is charged to every member. Reservation is all-or-nothing: the
 * members are locked in one global order (so parallel callers can't deadlock), checked, and either all
 * reserved or none.
 */
public final class BudgetSet {

    /** Output tokens reserved when nothing else says how long the answer may be. */
    public static final long DEFAULT_OUTPUT = 1024;
    /** Below this many affordable output tokens (or the requested amount, if smaller) a call is refused. */
    public static final long MIN_OUTPUT = 64;

    private static final BudgetSet EMPTY = new BudgetSet(List.of());

    private final List<Budget> members; // sorted by lock order, no duplicates

    private BudgetSet(List<Budget> members) {
        this.members = members;
    }

    public static BudgetSet of(Budget... budgets) {
        return EMPTY.with(budgets);
    }

    public static BudgetSet of(List<Budget> budgets) {
        return EMPTY.with(budgets.toArray(Budget[]::new));
    }

    /** This set plus more budgets (nulls ignored, duplicates collapsed). */
    public BudgetSet with(Budget... more) {
        Map<Budget, Boolean> seen = new IdentityHashMap<>();
        List<Budget> all = new ArrayList<>();
        for (Budget b : members) if (seen.put(b, true) == null) all.add(b);
        for (Budget b : more) if (b != null && seen.put(b, true) == null) all.add(b);
        if (all.isEmpty()) return EMPTY;
        all.sort(Comparator.comparingLong(Budget::order));
        return new BudgetSet(List.copyOf(all));
    }

    public List<Budget> members() {
        return members;
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    public boolean hasCostLimit() {
        return members.stream().anyMatch(b -> b.limits().cost() != null);
    }

    boolean constrainsOutput() {
        return members.stream().anyMatch(b -> b.limits().constrainsOutput());
    }

    /** Prices a call; {@code null} when no price is known (cost then counts as zero). */
    public interface Pricing {
        BigDecimal cost(long promptTokens, long completionTokens);

        /** Price of one output token. */
        BigDecimal perOutputToken();
    }

    /**
     * Reserves one call. With token or cost limits in the set, the output is capped to what every member
     * can still afford (at most {@code wanted}, or {@link #DEFAULT_OUTPUT} when {@code wanted} is null)
     * and the call is refused if that is below {@code min(wanted, MIN_OUTPUT)}. Without them, the output
     * is left as {@code wanted} (possibly null: no cap).
     *
     * @throws BudgetExceeded naming the first member that can't afford the call; nothing is reserved
     */
    public Lease reserve(long promptTokens, Long wanted, Pricing pricing) {
        if (members.isEmpty()) return new Lease(this, wanted, 0, 0, BigDecimal.ZERO);
        members.forEach(b -> b.lock.lock());
        BudgetEvent exhausted = null;
        Budget refusing = null;
        try {
            for (Budget b : members) b.rollLocked();
            // Calls.
            for (Budget b : members) {
                if (b.availableCalls() < 1) {
                    refusing = b; exhausted = b.exhaustedLocked();
                    throw new BudgetExceeded(b.name(), Dimension.CALLS, b.spentLocked(), b.limits(), b.windowEndLocked());
                }
            }
            BigDecimal promptCost = pricing == null ? BigDecimal.ZERO : pricing.cost(promptTokens, 0);
            // Cost of the prompt alone must fit.
            for (Budget b : members) {
                BigDecimal left = b.availableCost();
                if (left != null && left.compareTo(promptCost) < 0) {
                    refusing = b; exhausted = b.exhaustedLocked();
                    throw new BudgetExceeded(b.name(), Dimension.COST, b.spentLocked(), b.limits(), b.windowEndLocked());
                }
            }
            Long output = wanted;
            if (constrainsOutput()) {
                long target = wanted != null ? wanted : DEFAULT_OUTPUT;
                long need = Math.min(target, MIN_OUTPUT);
                long out = target;
                Budget binding = null;
                Dimension bindingDim = null;
                for (Budget b : members) {
                    if (b.limits().tokens() != null) {
                        long byTokens = b.availableTokens() - promptTokens;
                        if (byTokens < out) { out = byTokens; binding = b; bindingDim = Dimension.TOKENS; }
                    }
                    BigDecimal left = b.availableCost();
                    if (left != null && pricing != null && pricing.perOutputToken().signum() > 0) {
                        long byCost = left.subtract(promptCost)
                                .divide(pricing.perOutputToken(), 0, RoundingMode.FLOOR).longValue();
                        if (byCost < out) { out = byCost; binding = b; bindingDim = Dimension.COST; }
                    }
                }
                if (out < need) {
                    refusing = binding; exhausted = binding.exhaustedLocked();
                    throw new BudgetExceeded(binding.name(), bindingDim, binding.spentLocked(), binding.limits(), binding.windowEndLocked());
                }
                output = out;
            }
            long reservedTokens = promptTokens + (output == null ? 0 : output);
            BigDecimal reservedCost = pricing == null ? BigDecimal.ZERO : pricing.cost(promptTokens, output == null ? 0 : output);
            for (Budget b : members) b.reserveLocked(reservedTokens, 1, reservedCost);
            return new Lease(this, output, reservedTokens, 1, reservedCost);
        } finally {
            for (int i = members.size() - 1; i >= 0; i--) members.get(i).lock.unlock();
            if (exhausted != null) refusing.fire(List.of(exhausted));
        }
    }

    /** One reserved call, to be settled with its actual usage (or released if it never happened). */
    public static final class Lease {
        private final BudgetSet set;
        private final Long output;
        private final long tokens;
        private final long calls;
        private final BigDecimal cost;
        private boolean done;

        private Lease(BudgetSet set, Long output, long tokens, long calls, BigDecimal cost) {
            this.set = set;
            this.output = output;
            this.tokens = tokens;
            this.calls = calls;
            this.cost = cost;
        }

        /** The output tokens this call may produce, or null for no cap. */
        public Long output() {
            return output;
        }

        public void settle(Charge charge) {
            finish(charge);
        }

        public void release() {
            finish(null);
        }

        private void finish(Charge charge) {
            synchronized (this) {
                if (done) return;
                done = true;
            }
            List<Budget> members = set.members;
            List<List<BudgetEvent>> events = new ArrayList<>(members.size());
            members.forEach(b -> b.lock.lock());
            try {
                for (Budget b : members) events.add(b.settleLocked(tokens, calls, cost, charge));
            } finally {
                for (int i = members.size() - 1; i >= 0; i--) members.get(i).lock.unlock();
            }
            for (int i = 0; i < members.size(); i++) members.get(i).fire(events.get(i));
        }
    }
}
