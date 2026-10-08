package io.github.llm4j.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.budget.fixtures.ScriptedLLMClient;
import io.github.llm4j.budget.fixtures.Standard;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verification plan, Requirement 1 (V1.1–V1.6). */
class BudgetTest {

    @Test
    void v1_1_tokenLimitAlone() {
        Budget b = Budget.builder().tokens(1000).build();
        var client = Standard.client(new ScriptedLLMClient(), b);
        for (int i = 0; i < 3; i++) client.chat(Standard.request());
        assertThat(b.spent().tokens()).isEqualTo(450);
        assertThat(b.remaining().tokens().getAsLong()).isEqualTo(550);
        assertThat(b.remaining().calls()).isEmpty();
        assertThat(b.remaining().cost()).isEmpty();
    }

    @Test
    void v1_2_callLimit() {
        Budget b = Budget.builder().calls(3).build();
        ScriptedLLMClient model = new ScriptedLLMClient();
        var client = Standard.client(model, b);
        for (int i = 0; i < 3; i++) client.chat(Standard.request());
        assertThatThrownBy(() -> client.chat(Standard.request()))
                .isInstanceOfSatisfying(BudgetExceeded.class, e -> assertThat(e.dimension()).isEqualTo(Dimension.CALLS));
        assertThat(model.calls()).isEqualTo(3);
    }

    @Test
    void v1_3_noLimitsOnlyCounts() {
        Budget b = Budget.unlimited("counter");
        var client = Standard.client(new ScriptedLLMClient(), b);
        for (int i = 0; i < 10; i++) client.chat(Standard.request());
        assertThat(b.spent().tokens()).isEqualTo(1500);
        assertThat(b.spent().calls()).isEqualTo(10);
        assertThat(b.exhausted()).isFalse();
    }

    @Test
    void v1_4_warningFiresOnceWhenTheThresholdIsCrossed() {
        List<String> seen = new ArrayList<>();
        int[] call = {0};
        Budget b = Budget.builder().tokens(1000).warnAt(0.8)
                .listener(e -> seen.add(e.kind() + "@" + call[0])).build();
        var client = Standard.client(new ScriptedLLMClient(), b);
        for (int i = 1; i <= 6; i++) {
            call[0] = i;
            client.chat(Standard.request());
        }
        assertThat(seen).containsExactly("WARNING@6");
    }

    @Test
    void v1_5_aSetReservesAllOrNothing() {
        Budget run = Budget.builder().name("run").tokens(1000).build();
        Budget agent = Budget.builder().name("agent Writer").tokens(100).build();
        ScriptedLLMClient model = new ScriptedLLMClient();
        var client = Standard.client(model, run, agent);
        assertThatThrownBy(() -> client.chat(Standard.request()))
                .isInstanceOfSatisfying(BudgetExceeded.class, e -> assertThat(e.budget()).isEqualTo("agent Writer"));
        assertThat(model.calls()).isZero();
        assertThat(run.spent().tokens()).isZero();
        assertThat(run.remaining().tokens().getAsLong()).isEqualTo(1000);
        // nothing left reserved on run: a call that fits run alone is fully affordable again
        var runOnly = Standard.client(model, run);
        for (int i = 0; i < 6; i++) runOnly.chat(Standard.request());
        assertThat(run.spent().tokens()).isEqualTo(900);
    }

    @Test
    void v1_6_everyMemberIsCharged() {
        Budget run = Budget.builder().name("run").tokens(1000).build();
        Budget agent = Budget.builder().name("agent").tokens(500).build();
        var client = Standard.client(new ScriptedLLMClient(), run, agent);
        client.chat(Standard.request());
        client.chat(Standard.request());
        assertThat(run.spent().tokens()).isEqualTo(300);
        assertThat(agent.spent().tokens()).isEqualTo(300);
    }

    @Test
    void limitsMustBePositive() {
        assertThatThrownBy(() -> Budget.builder().tokens(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Budget.builder().calls(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Budget.builder().cost("0")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Budget.builder().cost("$0.50").build().limits().cost()).isEqualByComparingTo("0.50");
    }

    @Test
    void restoreAddsJournaledSpendWithoutRefusing() {
        Budget b = Budget.builder().tokens(1000).build();
        b.restore(new Spent(300, 300, 4, java.math.BigDecimal.ZERO, false, 0));
        assertThat(b.spent().tokens()).isEqualTo(600);
        assertThat(b.spent().calls()).isEqualTo(4);
        assertThat(b.remaining().tokens().getAsLong()).isEqualTo(400);
    }
}
