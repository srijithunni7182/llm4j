package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.agent.tool.Effectful;
import io.github.llm4j.agent.tool.Outcome;
import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a case leaves in the run journal for a fork of the run to find (spec loom-earned-autonomy R2.1, R2.5). */
class EvidenceTest {

    @TempDir
    Path dir;

    /** A tool that only looks things up, and says so for each call. */
    static final class Lookup implements Effectful {
        final AtomicInteger calls = new AtomicInteger();
        final String answer;

        Lookup(String answer) {
            this.answer = answer;
        }

        @Override public String getName() { return "Lookup"; }
        @Override public String getDescription() { return "looks a customer up"; }
        @Override public String execute(Map<String, Object> args) { calls.incrementAndGet(); return answer; }
        @Override public boolean isEffect(Map<String, Object> args) { return false; }
        @Override public EffectPolicy policy() { return EffectPolicy.DEFAULT; }
        @Override public String target(Map<String, Object> args) { return "crm"; }
        @Override public Outcome perform(Map<String, Object> args, String key) { throw new UnsupportedOperationException(); }
    }

    /** A tool that changes something, and is not an Effectful the runtime knows about. */
    static final class Writer implements Tool {
        @Override public String getName() { return "Writer"; }
        @Override public String getDescription() { return "writes"; }
        @Override public String execute(Map<String, Object> args) { return "written"; }
    }

    private DecideHarness harness(String tools) {
        return new DecideHarness(dir, new MemoryLedger(), new MemoryLevelStore(), Scripts2.withTools(tools));
    }

    @Test
    @Tag("EA-V2.5")
    void everyReadMadeWhileProposingIsRecordedWithItsResultAndTheCaseHoldsOnlyAPointerToTheRun() {
        DecideHarness h = harness("Lookup");
        h.tools.put("Lookup", new Lookup("customer since 2019, 3 earlier refunds"));
        h.toolCalls.add(new String[] {"Lookup", "{\"id\": \"c-1\"}"});
        h.toolCalls.add(new String[] {"Lookup", "{\"id\": \"c-2\"}"});
        RunJournal journal = RunJournal.inMemory();

        var executor = h.executor(h.newRun(journal), "run-1");
        executor.executeWorkflow("Triage", h.inputs("gold", 20));

        assertThat(journal.get("Triage/s0#decide-task").orElseThrow().value().toString()).contains("amount = 20").contains("approve, reject, escalate");
        assertThat(journal.get("Triage/s0#decide-proposal").orElseThrow().value()).isInstanceOf(Map.class);
        assertThat(journal.get("Triage/s0#level").orElseThrow().value().toString()).contains("watch");
        Map<?, ?> first = (Map<?, ?>) journal.get("Triage/s0#decide-evidence:0").orElseThrow().value();
        assertThat(first.get("tool")).isEqualTo("Lookup");
        assertThat(first.get("result")).isEqualTo("customer since 2019, 3 earlier refunds");
        assertThat(first.get("args").toString()).hasSize(16);
        assertThat(journal.get("Triage/s0#decide-evidence:1")).isPresent();
        assertThat(journal.get("Triage/s0#decide-evidence:2")).isEmpty();

        String ledgerText = h.ledger.records("Refund").toString();
        assertThat(ledgerText).doesNotContain("customer since 2019").doesNotContain("Decide Refund for this case").doesNotContain("decide-task");
        Case c = h.ledger.get("Refund", "run-1/Triage/s0").orElseThrow();
        assertThat(c.locator()).isEqualTo("run-1");
        assertThat(c.step()).isEqualTo("Triage/s0");
        assertThat(c.flags()).isEmpty();
    }

    @Test
    @Tag("EA-V2.5")
    void evidenceOverTheCapIsCutOffAndTheCaseIsMarked() {
        DecideHarness h = harness("Lookup");
        h.tools.put("Lookup", new Lookup("x".repeat(40 * 1024)));
        h.toolCalls.add(new String[] {"Lookup", "{\"id\": \"1\"}"});
        h.toolCalls.add(new String[] {"Lookup", "{\"id\": \"2\"}"}); // the second would take it past 64 KB
        RunJournal journal = RunJournal.inMemory();

        h.executor(h.newRun(journal), "run-1").executeWorkflow("Triage", h.inputs("gold", 20));

        assertThat(journal.get("Triage/s0#decide-evidence:0")).isPresent();
        assertThat(journal.get("Triage/s0#decide-evidence:1")).isEmpty();
        assertThat(((Map<?, ?>) journal.get("Triage/s0#decide-proposal").orElseThrow().value()).get("flags")).isEqualTo(List.of("evidence_truncated"));
    }

    @Test
    @Tag("EA-V2.5")
    void aCallThatCouldChangeSomethingWhileProposingMarksTheCaseSoAReplayWontTreatItAsClean() {
        DecideHarness h = harness("Writer");
        h.tools.put("Writer", new Writer());
        h.toolCalls.add(new String[] {"Writer", "{}"});
        RunJournal journal = RunJournal.inMemory();

        h.executor(h.newRun(journal), "run-1").executeWorkflow("Triage", h.inputs("gold", 20));

        assertThat(((Map<?, ?>) journal.get("Triage/s0#decide-proposal").orElseThrow().value()).get("flags")).isEqualTo(List.of("effects_during_proposal"));
        assertThat(journal.get("Triage/s0#decide-evidence:0")).as("an effect is not evidence").isEmpty();
    }

    @Test
    @Tag("EA-V2.6")
    void fieldsAndReasoningAreMaskedAndSecretsNeverReachTheLedger() {
        DecideHarness h = new DecideHarness(dir, new MemoryLedger(), new MemoryLevelStore(),
                Scripts2.refund("").replace("agent Triager {", "agent Triager {\n guard { pii: mask }")
                        .replace("remember:          amount, reason, customer_since", "remember:          amount, reason, customer_since, ticket")
                        .replace("workflow Triage", "tool Hook { use: webhook url: env.HOOK_URL }\nworkflow Triage"));
        h.agent = f -> new String[] {"approve", "mail ada@example.com via https://hooks.example.com/T1/hunter2-SECRET", "0.9"};
        var run = h.newRun(RunJournal.inMemory());
        run.env.put("HOOK_URL", "https://hooks.example.com/T1/hunter2-SECRET");
        var executor = h.executor(run, "run-1");
        Map<String, String> inputs = h.inputs("gold", 20);
        inputs.put("reason", "refund to ada@example.com, via https://hooks.example.com/T1/hunter2-SECRET");
        executor.executeWorkflow("Triage", inputs);

        String stored = h.ledger.records("Refund").toString();
        assertThat(stored).doesNotContain("ada@example.com").doesNotContain("hunter2-SECRET");
        assertThat(h.ledger.get("Refund", "run-1/Triage/s0").orElseThrow().fields().get("reason")).contains("refund to");
    }
}
