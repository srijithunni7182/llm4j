package io.github.llm4j.examples.expensedesk;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/**
 * The house rules for an expense claim, as code: which currencies are paid, the most each category
 * may claim, and the amount above which a person must approve. A model fills in the numbers it is
 * given; it never sees, and cannot change, these limits.
 *
 * <p>The outcomes are {@code approved} (pay at once), {@code needs_approval} (ask a person) and
 * {@code rejected} (with a reason).
 */
public final class ExpensePolicy implements Task {

    /** The name the script uses: {@code run ExpensePolicy(...)}. */
    public static final String NAME = "ExpensePolicy";

    /**
     * Claims up to this many US dollars are paid without asking anyone. It is never above the
     * smallest category limit: a model reads the category, so a claim that no person sees must be
     * one that every category would allow. A real evaluation showed a claim's text can talk the
     * model into another category; this bound is what makes that harmless.
     */
    public static final BigDecimal AUTOMATIC_LIMIT_USD = new BigDecimal("75");

    private static final Map<String, BigDecimal> USD_PER_UNIT =
            Map.of(
                    "USD", BigDecimal.ONE,
                    "EUR", new BigDecimal("1.10"),
                    "INR", new BigDecimal("0.012"));

    private static final Map<String, BigDecimal> CATEGORY_LIMIT_USD =
            Map.of(
                    "meals", new BigDecimal("75"),
                    "travel", new BigDecimal("1500"),
                    "equipment", new BigDecimal("2000"),
                    "other", new BigDecimal("200"));

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Checks an expense claim against the category limits and the automatic-payment limit.";
    }

    @Override
    public TaskEffect effect() {
        return TaskEffect.NONE;
    }

    @Override
    public TaskResult run(TaskContext context) {
        return decide(
                context.requireArg("amount", Double.class),
                context.requireArg("currency", String.class),
                context.requireArg("category", String.class));
    }

    /**
     * Decides one claim. Anything the rules do not name (a currency, a category, an amount that is
     * not positive) is refused: a missing rule never allows a payment.
     */
    public TaskResult decide(double amount, String currency, String category) {
        BigDecimal rate = USD_PER_UNIT.get(String.valueOf(currency).toUpperCase(Locale.ROOT));
        if (rate == null) {
            return TaskResult.rejected("claims in " + currency + " are not paid; use USD, EUR or INR");
        }
        BigDecimal limit = CATEGORY_LIMIT_USD.get(String.valueOf(category).toLowerCase(Locale.ROOT));
        if (limit == null) {
            return TaskResult.rejected("there is no rule for the category " + category);
        }
        if (!Double.isFinite(amount) || amount <= 0) {
            return TaskResult.rejected("the amount must be more than zero");
        }
        BigDecimal usd = BigDecimal.valueOf(amount).multiply(rate).setScale(2, RoundingMode.HALF_UP);
        if (usd.compareTo(limit) > 0) {
            return TaskResult.rejected(
                    "about " + usd + " USD is over the " + limit + " USD limit for " + category);
        }
        String outcome = usd.compareTo(AUTOMATIC_LIMIT_USD) <= 0 ? "approved" : "needs_approval";
        return TaskResult.outcome(outcome).with("usd", usd.doubleValue());
    }

    /** The most a claim of {@code category} may be, in US dollars, or null when there is no rule for it. */
    static BigDecimal limitOf(String category) {
        return CATEGORY_LIMIT_USD.get(category);
    }

    /** The categories the rules name. */
    static java.util.Set<String> categories() {
        return CATEGORY_LIMIT_USD.keySet();
    }

    /** The limits, for a page that shows the rules beside the claims. */
    public static Map<String, Object> describe() {
        return Map.of(
                "automaticLimitUsd", AUTOMATIC_LIMIT_USD,
                "categoryLimitsUsd", new java.util.TreeMap<>(CATEGORY_LIMIT_USD),
                "currencies", new java.util.TreeSet<>(USD_PER_UNIT.keySet()));
    }
}
