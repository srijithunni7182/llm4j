package io.github.llm4j.loom.explain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.runtime.RunJournal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code weave why} says what a steer's record knows: delivered, not delivered, or unknown after a crash. */
class WhySteerSummaryTest {

    private static String summaryOfSteer(int delivered) {
        return Why.summary(new RunJournal.Entry("child-steer", Map.of("agent", "Looper", "delivered", delivered)));
    }

    @Test
    void aSteerTakenByAnAgentIsDelivered() {
        assertThat(summaryOfSteer(1)).isEqualTo("steered Looper (delivered)");
    }

    @Test
    void aSteerNoAgentTookIsNotDelivered() {
        assertThat(summaryOfSteer(0)).isEqualTo("steered Looper (not delivered)");
    }

    @Test
    void aSteerLeftAsAnIntentByACrashIsNotReportedAsDelivered() {
        assertThat(summaryOfSteer(-1))
                .contains("delivery unknown")
                .doesNotContain("(delivered)")
                .doesNotContain("(not delivered)");
    }
}
