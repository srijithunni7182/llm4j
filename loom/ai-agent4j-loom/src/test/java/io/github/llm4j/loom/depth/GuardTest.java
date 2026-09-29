package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoadException;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.runtime.FileRunJournal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification plan V5: agent guards (PII and bias). */
class GuardTest {

    @TempDir
    Path dir;

    static final String TASK = "Write to asha@example.com or call 555-123-4567 about the order";

    static String script(String guard) {
        return """
                agent Support {
                    model: "m"
                    tools: [Lookup]
                    guard { %s }
                }
                workflow Main() {
                    delegate "{task}" to Support -> reply
                        on_failure { note "stopped" }
                }
                """.formatted(guard);
    }

    Harness harness(String... answers) {
        Harness h = new Harness(dir).answers(answers);
        h.tools.register("Lookup", Harness.recording("Lookup", new ArrayList<>(), "Customer: Ravi, ravi@shop.in, SSN 123-45-6789"));
        return h;
    }

    @Test
    void v5_1_maskKeepsPersonalDataFromTheModelAndTheResult() {
        Harness h = harness(Harness.call("Lookup", "{\"q\": \"order\"}"), Harness.done("Reply sent to asha@example.com"));
        HarnessExecutor e = h.ready(script("pii: mask"));
        e.executeWorkflow("Main", Map.of("task", TASK));

        String seen = h.seen();
        assertThat(seen).doesNotContain("asha@example.com").doesNotContain("555-123-4567")
                .doesNotContain("ravi@shop.in").doesNotContain("123-45-6789")
                .contains("[EMAIL]").contains("[PHONE]").contains("[SSN]");
        assertThat(h.requests).hasSize(2);
        assertThat(e.getContext().getVariable("reply")).isEqualTo("Reply sent to [EMAIL]");
        assertThat(h.audit).anySatisfy(a -> assertThat(a).startsWith("pii_masked").contains("where=model input"))
                .anySatisfy(a -> assertThat(a).startsWith("pii_masked").contains("where=answer").contains("EMAIL=1"))
                .noneSatisfy(a -> assertThat(a).contains("asha@example.com"));
    }

    @Test
    void v5_2_blockStopsATaskWithPersonalDataBeforeAnyModelCall() {
        Harness h = harness();
        HarnessExecutor e = h.ready(script("pii: block"));
        e.executeWorkflow("Main", Map.of("task", TASK));
        assertThat(h.requests).isEmpty();
        assertThat(e.getContext().getAll()).doesNotContainKey("reply");
        assertThat(h.audit).anySatisfy(a -> assertThat(a).startsWith("pii_blocked").contains("where=task"));

        // _error names the types, never the values
        Harness h2 = harness();
        HarnessExecutor e2 = h2.ready(script("pii: block").replace("note \"stopped\"", "delegate \"{_error}\" to Reporter -> why")
                + "agent Reporter { model: \"m\" }\n");
        e2.executeWorkflow("Main", Map.of("task", TASK));
        assertThat(h2.task(0)).contains("contains personal data (EMAIL, PHONE)").doesNotContain("asha@");
    }

    @Test
    void v5_2_blockStopsAnAnswerWithPersonalData() {
        Harness h = harness(Harness.done("Her email is asha@example.com"));
        HarnessExecutor e = h.ready(script("pii: block"));
        e.executeWorkflow("Main", Map.of("task", "Who is the customer?"));
        assertThat(h.requests).hasSize(1); // not retried
        assertThat(e.getContext().getAll()).doesNotContainKey("reply");
        assertThat(h.audit).anySatisfy(a -> assertThat(a).startsWith("pii_blocked").contains("where=answer"));
    }

    @Test
    void v5_3_warnRecordsAndCarriesOn() {
        Harness h = harness(Harness.done("Contact asha@example.com"));
        HarnessExecutor e = h.ready(script("pii: warn"));
        e.executeWorkflow("Main", Map.of("task", TASK));
        assertThat(h.seen()).contains("asha@example.com");
        assertThat(e.getContext().getVariable("reply")).isEqualTo("Contact asha@example.com");
        assertThat(h.audit).anySatisfy(a -> assertThat(a).startsWith("pii_detected").contains("where=task"))
                .anySatisfy(a -> assertThat(a).startsWith("pii_detected").contains("where=answer"));
    }

    @Test
    void v5_4_biasWarnRecordsAndBlockStops() {
        Harness warn = harness(Harness.done("Women are bad at math, so hire men."));
        HarnessExecutor w = warn.ready(script("bias: warn"));
        w.executeWorkflow("Main", Map.of("task", "Who should we hire?"));
        assertThat(w.getContext().getVariable("reply")).isEqualTo("Women are bad at math, so hire men.");
        assertThat(warn.audit).anySatisfy(a -> assertThat(a).startsWith("bias_detected").contains("type=GENDER").contains("severity=HIGH"));
        assertThat(warn.trace).anySatisfy(t -> assertThat(t.type()).isEqualTo(TraceEvent.GUARD));

        Harness block = harness(Harness.done("Women are bad at math, so hire men."));
        HarnessExecutor b = block.ready(script("bias: block"));
        b.executeWorkflow("Main", Map.of("task", "Who should we hire?"));
        assertThat(b.getContext().getAll()).doesNotContainKey("reply");
        assertThat(block.audit).anySatisfy(a -> assertThat(a).startsWith("bias_blocked"));

        Harness fair = harness(Harness.done("Hire the candidate with the strongest portfolio."));
        HarnessExecutor f = fair.ready(script("bias: block"));
        f.executeWorkflow("Main", Map.of("task", "Who should we hire?"));
        assertThat(f.getContext().getVariable("reply")).isEqualTo("Hire the candidate with the strongest portfolio.");
    }

    @Test
    void v5_5_aJudgeModelIsUsedAndBudgeted() {
        List<String> judged = new ArrayList<>();
        Harness h = harness(Harness.done("Our team is great."));
        HarnessExecutor e = h.executor("""
                budget { tokens: 1000 }
                """ + script("bias: block  bias_model: \"judge\""), model -> model.equals("judge")
                ? new io.github.llm4j.LLMClient() {
                    @Override
                    public io.github.llm4j.model.LLMResponse chat(io.github.llm4j.model.LLMRequest r) {
                        judged.add(r.getMessages().get(1).getContent());
                        return io.github.llm4j.model.LLMResponse.builder().tokenUsage(40, 10, 50).content(
                                "{\"findings\": [{\"type\": \"AGE\", \"severity\": \"HIGH\", \"text\": \"great\", \"explanation\": \"ageist\"}]}").build();
                    }

                    @Override
                    public java.util.stream.Stream<io.github.llm4j.model.LLMResponse> chatStream(io.github.llm4j.model.LLMRequest r) {
                        return java.util.stream.Stream.of(chat(r));
                    }
                } : h.client(model), null);
        e.initialize();
        e.executeWorkflow("Main", Map.of("task", "Describe the team"));
        assertThat(judged).singleElement().satisfies(t -> assertThat(t).contains("Our team is great."));
        assertThat(e.getContext().getAll()).doesNotContainKey("reply");
        assertThat(e.getRunBudget().spent().tokens()).isEqualTo(15 + 50); // agent call + judge call
    }

    @Test
    void v5_6_replayedStepsAreNotCheckedAgain() {
        Path journal = dir.resolve("journal.json");
        Harness first = harness(Harness.done("Women are bad at math."));
        first.journal = new FileRunJournal(journal);
        first.ready(script("bias: warn")).executeWorkflow("Main", Map.of("task", "x"));
        long before = first.audit.stream().filter(a -> a.startsWith("bias_detected")).count();
        assertThat(before).isPositive();

        Harness again = harness();
        again.journal = new FileRunJournal(journal);
        again.ready(script("bias: warn")).executeWorkflow("Main", Map.of("task", "x"));
        assertThat(again.audit).noneSatisfy(a -> assertThat(a).startsWith("bias_detected"));
        assertThat(again.requests).isEmpty();
    }

    @Test
    void v5_6_guardSettingsAreChecked() {
        assertThatThrownBy(() -> harness().ready(script("pii: hide  bias: shout  colour: \"red\"")))
                .isInstanceOfSatisfying(LoomLoadException.class, e -> assertThat(e.getMessage())
                        .contains("guard pii: hide is not one of mask, block, warn")
                        .contains("guard bias: shout is not one of warn, block")
                        .contains("unknown guard setting colour"));
        assertThatThrownBy(() -> harness().ready(script("bias_model: \"judge\"")))
                .hasMessageContaining("guard needs pii:").hasMessageContaining("guard bias_model needs bias:");
    }
}
