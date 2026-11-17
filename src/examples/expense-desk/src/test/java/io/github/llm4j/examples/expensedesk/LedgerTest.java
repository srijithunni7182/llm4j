package io.github.llm4j.examples.expensedesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.task.TaskContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LedgerTest {

    private final Ledger ledger = new Ledger();

    @Test
    void paysOnceForOneKey() {
        Ledger.Payment first = ledger.pay("step-1", "Asha", "City Cabs", 42.5, "USD");
        Ledger.Payment again = ledger.pay("step-1", "Asha", "City Cabs", 42.5, "USD");

        assertThat(again).isEqualTo(first);
        assertThat(ledger.payments()).containsExactly(first);
    }

    @Test
    void givesEachKeyItsOwnReference() {
        ledger.pay("step-1", "Asha", "City Cabs", 42.5, "USD");
        ledger.pay("step-2", "Ravi", "Blue Air", 900, "USD");

        assertThat(ledger.payments()).extracting(Ledger.Payment::reference).containsExactly("PAY-0001", "PAY-0002");
    }

    @Test
    void refusesAPaymentWithNoKey() {
        assertThatThrownBy(() -> ledger.pay(" ", "Asha", "City Cabs", 42.5, "USD")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ledger.payments()).isEmpty();
    }

    @Test
    void theTaskPaysWithTheStepsIdempotencyKeySoARetryPaysNothingMore() {
        Reimburse task = new Reimburse(ledger);
        Map<String, Object> args = Map.of("employee", "Asha", "merchant", "City Cabs", "amount", 42.5, "currency", "USD");
        TaskContext step = TaskContext.of(args, Map.of(), "main/s3", "run-1/main/s3");

        assertThat(task.run(step).toMap()).containsEntry("value", "PAY-0001");
        assertThat(task.run(step).toMap()).containsEntry("value", "PAY-0001");
        assertThat(ledger.payments()).hasSize(1);
        assertThat(task.policy().idempotent()).isTrue();
    }
}
