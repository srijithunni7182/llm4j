package io.github.llm4j.loom.ladder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LadderDef;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A whole ladder in plain sentences, for the person who has to sign it off. */
@Tag("L8")
class LadderDescriberTest {

    @TempDir
    Path dir;

    @Test
    void theRefundLadderReadsAsSentences() throws IOException {
        LadderHarness h = LadderHarness.fromResource("refund.loom", dir);
        LadderDef l = h.script.getLadders().get(0);
        String text = LadderDescriber.describe(l, h.decision);
        assertThat(text).contains("Ladder RefundLadder")
                .contains("Used by decision Refund, which may climb as far as act.")
                .contains("A new scope starts on watch.")
                .contains("Rung watch: a person answers without seeing the agent's proposal.")
                .contains("Rung suggest: a person sees the agent's proposal and accepts, edits or rejects it.")
                .contains("Rung act: the agent's proposal takes effect without asking.")
                .contains("The agent climbs to suggest when:")
                .contains("the number of cases is at least 10")
                .contains("a cautious estimate of the share of cases where agrees is at least 60%")
                .contains("The agent slips from act to suggest when any of these holds:")
                .contains("the number of the last 50 cases where dangerous is at least 2")
                .contains("10% of its cases are sent to a person who does not see the proposal.")
                .contains("A person is also asked, whatever the rung, when any of these holds:")
                .contains("the case's amount is more than 200")
                .contains("Moving up needs approval from risk-owner.");
    }

    @Test
    void parametersAndAutomaticClimbingAreSaid() throws IOException {
        LadderHarness h = LadderHarness.fromResource("blog_publisher.loom", dir);
        String text = LadderDescriber.describe(h.script.getLadders().get(0), h.decision);
        assertThat(text).contains("baseline_views = 1200").contains("Rung edit: a person sees").contains("the number of cases where careful is at least 6");
        String auto = LadderDescriber.describe(h.script.getLadders().get(0), null);
        assertThat(auto).contains("Moving up needs approval from editor-in-chief.").doesNotContain("Used by");
    }
}
