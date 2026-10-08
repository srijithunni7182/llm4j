package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Running {@code decide}: who decides at each level, what is recorded, and what the person is never shown (spec loom-earned-autonomy R2, R3, R4). */
class DecideTest {

    @TempDir
    Path dir;

    private DecideHarness harness(String trustExtra) {
        return new DecideHarness(dir, new MemoryLedger(), new MemoryLevelStore(), Scripts2.refund(trustExtra));
    }

    @Test
    @Tag("EA-V3.2")
    @Tag("EA-V2.1")
    void atWatchThePersonsVerdictTakesEffectAndTheProposalNeverDoes() {
        DecideHarness h = harness("");
        h.agent = f -> new String[] {"approve", "the customer is long standing", "0.8"};
        h.person = f -> "reject";

        Map<String, Object> vars = h.runCase("run-1", h.inputs("gold", 20));

        assertThat(vars.get("verdict")).isEqualTo("reject");
        assertThat(vars.get("verdict_proposal")).isEqualTo("approve");
        assertThat(vars.get("verdict_level")).isEqualTo("watch");
        assertThat(h.asked).hasSize(1);

        Case c = h.ledger.get("Refund", "run-1/Triage/s0").orElseThrow();
        assertThat(c.scope()).isEqualTo("gold");
        assertThat(c.fields()).containsEntry("amount", "20").containsEntry("reason", "damaged").containsEntry("customer_since", "2020");
        assertThat(c.locator()).isEqualTo("run-1");
        assertThat(c.step()).isEqualTo("Triage/s0");
        assertThat(c.level()).isEqualTo(Level.WATCH);
        assertThat(c.epoch()).isEqualTo(1);
        assertThat(c.identity()).hasSize(64);
        assertThat(c.proposal()).isEqualTo("approve");
        assertThat(c.reasoning()).isEqualTo("the customer is long standing");
        assertThat(c.confidence()).isEqualTo(0.8);
        assertThat(c.verdict()).isEqualTo("reject");
        assertThat(c.decider()).isEqualTo("support-lead");
        assertThat(c.shown()).isFalse();
        assertThat(c.blindEvidence()).isTrue();
        assertThat(c.millis()).isNotNull();
    }

    @Test
    @Tag("EA-V4.1")
    void theProposalIsInNoStringAPersonOrAListenerSeesBeforeTheyAnswer() {
        DecideHarness h = harness("");
        String marker = "ZEBRA-MARKER-7731";
        h.agent = f -> new String[] {"approve", "because " + marker, "0.77"};
        List<String> seenAtAnswer = new java.util.ArrayList<>();
        h.person = f -> {
            seenAtAnswer.addAll(h.trace);
            seenAtAnswer.addAll(h.audit);
            seenAtAnswer.addAll(h.last.audit);
            seenAtAnswer.addAll(h.last.auditData.stream().map(Object::toString).toList());
            return "approve";
        };

        h.runCase("run-1", h.inputs("gold", 20));

        assertThat(h.asked.get(0)).doesNotContain(marker).doesNotContain("0.77").doesNotContain("proposes").doesNotContain("reasoning");
        assertThat(String.join("\n", seenAtAnswer)).doesNotContain(marker).doesNotContain("0.77");
        assertThat(String.join("\n", h.trace)).contains("proposal recorded (hidden)");
        // after the verdict the held entries are delivered, and the ledger has the reasoning
        assertThat(h.ledger.get("Refund", "run-1/Triage/s0").orElseThrow().reasoning()).contains(marker);
    }

    @Test
    @Tag("EA-V3.2")
    void atSuggestThePersonSeesTheProposalAndTheirAnswerIsTheVerdictEvenWhenItOverridesIt() {
        DecideHarness h = harness("");
        h.levels.compareAndSet("Refund", "gold", null, new LevelState(Level.SUGGEST, 1, new DecideHarness(dir, h.ledger, h.levels, h.script).hashOfRefund(), false, DecideHarness.T0, "test", 1));
        h.agent = f -> new String[] {"approve", "fine by policy", "0.9"};
        h.person = f -> "reject";

        Map<String, Object> vars = h.runCase("run-1", h.inputs("gold", 20));

        assertThat(vars.get("verdict")).isEqualTo("reject");
        assertThat(vars.get("verdict_level")).isEqualTo("suggest");
        assertThat(h.asked.get(0)).contains("The agent proposes: approve").contains("fine by policy").contains("confidence 0.9");
        Case c = h.ledger.get("Refund", "run-1/Triage/s0").orElseThrow();
        assertThat(c.shown()).isTrue();
        assertThat(c.blindEvidence()).isFalse();
    }

    @Test
    @Tag("EA-V3.2")
    void atActTheProposalTakesEffectAndNoPersonIsAsked() {
        DecideHarness h = harness("");
        h.levels.compareAndSet("Refund", "gold", null, new LevelState(Level.ACT, 1, h.hashOfRefund(), false, DecideHarness.T0, "test", 1));
        h.agent = f -> new String[] {"approve", "ok", "0.99"};

        Map<String, Object> vars = h.runCase("run-1", h.inputs("gold", 20));

        assertThat(vars.get("verdict")).isEqualTo("approve");
        assertThat(vars.get("verdict_level")).isEqualTo("act");
        assertThat(h.asked).isEmpty();
        Case c = h.ledger.get("Refund", "run-1/Triage/s0").orElseThrow();
        assertThat(c.decidedByAgent()).isTrue();
        assertThat(c.blindEvidence()).isFalse();
    }

    @Test
    @Tag("EA-V3.1")
    void aNewScopeStartsAtTheStartLevelAndScopesAreSeparate() {
        DecideHarness h = harness("");
        h.levels.compareAndSet("Refund", "gold", null, new LevelState(Level.ACT, 1, h.hashOfRefund(), false, DecideHarness.T0, "test", 1));

        Map<String, Object> gold = h.runCase("run-1", h.inputs("gold", 20));
        Map<String, Object> basic = h.runCase("run-2", h.inputs("basic", 20));

        assertThat(gold.get("verdict_level")).isEqualTo("act");
        assertThat(basic.get("verdict_level")).isEqualTo("watch");
        assertThat(h.levels.scopes("Refund")).containsOnlyKeys("gold", "basic");
    }

    @Test
    @Tag("EA-V1.6")
    void theVerdictAndItsPartsAreUsableInLaterSteps() {
        DecideHarness h = harness("");
        h.person = f -> "reject";
        h.script = h.script.replace("note \"verdict", "alt (verdict == \"reject\") { note \"rejected\" } else { note \"other\" }\n    note \"verdict");
        h.runCase("run-1", h.inputs("gold", 20));
        assertThat(h.lastExecutor.getContext().getAll()).containsEntry("verdict", "reject");
    }
}
