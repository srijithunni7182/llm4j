package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ladder.TrustDesugar;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a ladder cannot say in a line stays on the original engine, and still runs there, with its own records and level store. */
@Tag("L7")
class OriginalEngineFallbackTest {

    @TempDir
    Path dir;

    private DecideHarness harness(String trustExtra) {
        return new DecideHarness(dir, new MemoryLedger(), new MemoryLevelStore(), Scripts2.refund(trustExtra));
    }

    @Test
    void aDecisionThatAsksAPersonAfterNCasesADayKeepsRunningOnTheOriginalEngine() {
        DecideHarness h = harness("always ask a person after 100 cases a day");
        var decision = h.parsed().getDecisions().get(0);
        assertThat(decision.getLadder()).isNull();
        assertThat(TrustDesugar.of(decision, Set.of()).unsupported()).contains("calendar days");

        h.agent = f -> new String[] {"approve", "fine", "0.9"};
        for (int i = 0; i < 5; i++) h.runCase("run-" + i, h.inputs("gold", 10 + i));
        assertThat(h.levelOf("gold")).as("climbed by the original rules").isEqualTo(Level.SUGGEST);
        assertThat(h.levels.get("Refund", "gold")).as("in the original level store").isPresent();
        List<String> kinds = h.ledger.records("Refund").stream().map(Rec::kind).toList();
        assertThat(kinds).contains(Rec.DECIDED).doesNotContain(Rec.RESPONDED);
    }

    @Test
    void aDecisionThatStartsAboveWatchKeepsRunningOnTheOriginalEngine() {
        DecideHarness h = harness("");
        h.script = h.script.replace("start at watch", "start at suggest");
        assertThat(h.parsed().getDecisions().get(0).getLadder()).isNull();
        Map<String, Object> out = h.runCase("run-1", h.inputs("gold", 20));
        assertThat(out.get("verdict_level")).isEqualTo("suggest");
        assertThat(h.asked.get(0)).contains("The agent proposes");
    }

    @Test
    void theSameDecisionWithNothingUnusualRunsOnTheLadderWrittenFromItsTrustBlock() {
        DecideHarness h = harness("");
        assertThat(h.parsed().getDecisions().get(0).isLadderFromTrust()).isTrue();
        h.runCase("run-1", h.inputs("gold", 20));
        assertThat(h.ledger.records("Refund").stream().map(Rec::kind).toList()).contains(Rec.RESPONDED).doesNotContain(Rec.DECIDED);
        assertThat(h.levels.get("Refund", "gold")).as("nothing in the original level store").isEmpty();
    }
}
