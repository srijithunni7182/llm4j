package io.github.llm4j.examples.expensedesk;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import java.util.Objects;

/**
 * Pays a claim into the {@link Ledger}. It changes the outside world, so it says so: the runtime
 * journals it, never repeats it when its outcome is unknown, and hands it the same idempotency key
 * on a retry or a resume, which the ledger uses to pay once.
 */
public final class Reimburse implements Task {

    /** The name the script uses: {@code run Reimburse(...)}. */
    public static final String NAME = "Reimburse";

    private final Ledger ledger;

    /** For {@code weave check} and {@code weave test}, which find tasks by their class: a ledger of its own. */
    public Reimburse() {
        this(new Ledger());
    }

    /** Pays into the application's ledger. */
    public Reimburse(Ledger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Pays an approved expense claim, once.";
    }

    @Override
    public EffectPolicy policy() {
        return new EffectPolicy(EffectPolicy.OnUnknown.SKIP, true, 1);
    }

    @Override
    public TaskResult run(TaskContext context) {
        Ledger.Payment payment =
                ledger.pay(
                        context.idempotencyKey(),
                        context.requireArg("employee", String.class),
                        context.requireArg("merchant", String.class),
                        context.requireArg("amount", Double.class),
                        context.requireArg("currency", String.class));
        return TaskResult.value(payment.reference());
    }
}
