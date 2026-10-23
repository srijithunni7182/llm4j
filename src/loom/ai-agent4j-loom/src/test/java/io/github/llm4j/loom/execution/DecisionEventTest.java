package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.eval.MockModels;
import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R5, R6, R44 of spec 13: a run tells a listener where it chose a way, with the values and where
 * they came from; a run with no listener is exactly what it was.
 */
class DecisionEventTest {

    @TempDir Path dir;

    static final String SCRIPT =
            """
            agent A { model: "m" system: "x" }
            workflow Main(who, secret) {
                set mood = "calm"
                delegate "Greet {who}" to A -> greeting
                alt (mood == "calm") {
                    note "calm"
                } else {
                    note "agitated"
                }
                alt (secret == "hunter2") {
                    note "hit"
                }
                alt (who == "x") {
                    note "x"
                }
                return greeting
            }
            """;

    record Observed(
            Map<String, RunJournal.Entry> journal,
            List<String> trace,
            List<DecisionPoint> decisions) {}

    private Observed run(DecisionListener extra, Set<String> masked, Map<String, String> input)
            throws Exception {
        Path file = dir.resolve("main.loom");
        Files.writeString(file, SCRIPT);
        HarnessExecutor executor =
                new HarnessExecutor(
                        new LoomLoader().load(file.toString()),
                        new ToolRegistry(),
                        new MockModels());
        List<String> trace = new ArrayList<>();
        executor.addTraceListener(
                e -> trace.add(e.type() + "|" + e.agent() + "|" + e.step() + "|" + e.text()));
        List<DecisionPoint> decisions = new ArrayList<>();
        if (extra != null)
            executor.setDecisionListener(
                    d -> {
                        decisions.add(d);
                        extra.decided(d);
                    },
                    masked);
        executor.initialize();
        executor.executeWorkflow("Main", input);
        Map<String, RunJournal.Entry> journal = new LinkedHashMap<>(executor.getJournal().all());
        executor.shutdown();
        return new Observed(journal, trace, decisions);
    }

    @Test
    void eachChoiceNamesItsConditionTheValuesAndWhereTheyCameFrom() throws Exception {
        Observed o = run(d -> {}, Set.of(), Map.of("who", "bob", "secret", "none"));
        DecisionPoint first = o.decisions().get(0);
        assertThat(first.kind()).isEqualTo("alt");
        assertThat(first.condition()).isEqualTo("mood == \"calm\"");
        assertThat(first.result()).isTrue();
        assertThat(first.taken()).isEqualTo("then");
        assertThat(first.notTaken()).containsExactly("else: note");
        DecisionPoint.Operand mood = first.operands().get(0);
        assertThat(mood.expr()).isEqualTo("mood");
        assertThat(mood.value()).isEqualTo("calm");
        assertThat(mood.boundBy()).isEqualTo("set mood");
        assertThat(mood.boundLine()).isEqualTo(3);
        assertThat(mood.boundStep()).isEqualTo("Main/s0");
        // an input has no binding statement
        DecisionPoint third = o.decisions().get(2);
        assertThat(third.operands().get(0).boundBy()).isNull();
    }

    @Test
    void aMaskedVariableIsNeverShownButTheChoiceIsStillExplained() throws Exception {
        Observed o = run(d -> {}, Set.of("secret"), Map.of("who", "bob", "secret", "s3cret-input"));
        DecisionPoint secret = o.decisions().get(1);
        assertThat(secret.operands().get(0).value()).isEqualTo(DecisionPoint.MASKED);
        assertThat(secret.result()).isFalse();
        assertThat(secret.toString()).doesNotContain("s3cret-input");
    }

    @Test
    void aLongValueIsCutAndItsLengthKept() throws Exception {
        String long300 = "y".repeat(300);
        Observed o = run(d -> {}, Set.of(), Map.of("who", long300, "secret", "z"));
        DecisionPoint.Operand who = o.decisions().get(2).operands().get(0);
        assertThat(who.length()).isEqualTo(300);
        assertThat(who.value()).hasSize(DecisionPoint.DISPLAY_LIMIT + 1).endsWith("…");
    }

    @Test
    void aRunWithNoListenerIsExactlyWhatItWas() throws Exception {
        Observed watched = run(d -> {}, Set.of(), Map.of("who", "bob", "secret", "none"));
        Observed unwatched = run(null, Set.of(), Map.of("who", "bob", "secret", "none"));
        assertThat(unwatched.decisions()).isEmpty();
        assertThat(watched.journal()).isEqualTo(unwatched.journal());
        assertThat(watched.trace()).isEqualTo(unwatched.trace());
    }

    @Test
    void conditionsAreDisplayedWithTheirQuotesAndSpaces() {
        assertThat(io.github.llm4j.loom.runtime.ConditionEvaluator.display("a.b==yes"))
                .isEqualTo("a.b == \"yes\"");
        assertThat(io.github.llm4j.loom.runtime.ConditionEvaluator.display("score>=7"))
                .isEqualTo("score >= 7");
        assertThat(io.github.llm4j.loom.runtime.ConditionEvaluator.display("done is ok"))
                .isEqualTo("done is ok");
        assertThat(io.github.llm4j.loom.runtime.ConditionEvaluator.operands("a.b==yes"))
                .containsExactly("a.b");
        assertThat(io.github.llm4j.loom.runtime.ConditionEvaluator.operands("flag"))
                .containsExactly("flag");
        assertThat(io.github.llm4j.loom.runtime.ConditionEvaluator.operands("x is not ok"))
                .containsExactly("x");
    }
}
