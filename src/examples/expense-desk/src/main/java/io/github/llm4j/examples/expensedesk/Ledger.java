package io.github.llm4j.examples.expensedesk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Where payments are recorded. It deduplicates by the key the caller hands it, the way a real
 * payments API does: paying twice with one key returns the first payment and moves no more money.
 * Thread-safe.
 */
public final class Ledger {

    /** One payment. {@code reference} is what the employee is told. */
    public record Payment(
            String reference, String employee, String merchant, double amount, String currency) {}

    private final Map<String, Payment> byKey = new LinkedHashMap<>();

    /**
     * Pays once for {@code idempotencyKey}: a second call with the same key returns the payment
     * the first one made.
     */
    public synchronized Payment pay(
            String idempotencyKey, String employee, String merchant, double amount, String currency) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("a payment needs an idempotency key");
        }
        Payment earlier = byKey.get(idempotencyKey);
        if (earlier != null) return earlier;
        Payment payment =
                new Payment(
                        String.format("PAY-%04d", byKey.size() + 1),
                        Objects.requireNonNull(employee, "employee"),
                        Objects.requireNonNull(merchant, "merchant"),
                        amount,
                        Objects.requireNonNull(currency, "currency"));
        byKey.put(idempotencyKey, payment);
        return payment;
    }

    /** Every payment made, oldest first. */
    public synchronized List<Payment> payments() {
        return List.copyOf(new ArrayList<>(byKey.values()));
    }
}
