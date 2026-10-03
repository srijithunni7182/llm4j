package io.github.llm4j.eval.export;

import static io.github.llm4j.eval.judge.LlmJudgeCondition.llmJudged;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.eval.assertions.WorkflowAssertions;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.judge.InMemoryJudgeCache;
import io.github.llm4j.eval.judge.OutputExtractor;
import io.github.llm4j.eval.report.EvalRecorder;
import io.github.llm4j.eval.support.StubJudge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The small API conveniences that keep a real evaluation to a few lines. */
class FriendlyApiTest {

    @TempDir Path root;

    @BeforeEach
    void setUp() {
        EvalRun.resetForTests();
        JudgeStats.reset();
        System.setProperty(ExportConfig.DIR, root.toString());
        System.setProperty(ExportConfig.RUN_ID, "RUN-FRIENDLY-0001");
        EvalRecorder.reset();
        EvalRecorder.activate();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(ExportConfig.DIR);
        System.clearProperty(ExportConfig.RUN_ID);
        EvalRun.resetForTests();
        JudgeStats.reset();
        EvalRecorder.reset();
    }

    @Test
    void oneJudgeCallIsFiledUnderEveryDimensionOfTheScenario() throws Exception {
        EvalScenario s =
                new EvalScenario(
                        "s1",
                        "Is QLL-7 real?",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of("fact-checking", "safety"),
                        null);
        StubJudge judge = StubJudge.rating(m -> 4);
        EvalRun.get().bindTest("com.acme.T", "t");
        EvalRun.get().bindScenario(s);
        boolean ok =
                llmJudged("Rubric")
                        .criteria("c")
                        .scenario(s)
                        .judge(judge)
                        .cache(InMemoryJudgeCache.create())
                        .threshold(0.5)
                        .build()
                        .matches("It cannot be verified.");
        EvalRun.get().unbind();
        EvalRun.get().finish();

        assertThat(ok).isTrue();
        Path dir = root.resolve("runs/RUN-FRIENDLY-0001");
        List<String> lines = Files.readAllLines(dir.resolve("evaluations.jsonl"));
        assertThat(lines).hasSize(2);
        List<String> metrics = new ArrayList<>();
        for (String l : lines) {
            metrics.add(RunWriter.MAPPER.readTree(l).path("metric").asText());
        }
        assertThat(metrics).containsExactly("rubric-fact-checking", "rubric-safety");
        JsonNode run = RunWriter.MAPPER.readTree(dir.resolve("run.json").toFile());
        assertThat(run.path("metrics").toString())
                .contains("\"dimension\":\"fact-checking\"")
                .contains("\"dimension\":\"safety\"");
        assertThat(JudgeStats.snapshot("judge").toMap().toString()).contains("calls");
    }

    @Test
    void withoutDimensionsTheVerdictIsRecordedOnceAsBefore() throws Exception {
        EvalRun.get().bindTest("com.acme.T", "t");
        llmJudged("Correctness")
                .criteria("c")
                .judge(StubJudge.rating(m -> 5))
                .threshold(0.5)
                .build()
                .matches("x");
        EvalRun.get().unbind();
        EvalRun.get().finish();
        assertThat(Files.readAllLines(root.resolve("runs/RUN-FRIENDLY-0001/evaluations.jsonl")))
                .hasSize(1);
    }

    @Test
    void outputExtractorIsPublic() {
        AgentResult r = AgentResult.builder().finalAnswer("hello").completed(true).build();
        assertThat(OutputExtractor.extract(r)).isEqualTo("hello");
        assertThat(OutputExtractor.extract("plain")).isEqualTo("plain");
    }

    @Test
    void toolArgumentCanBeCheckedForAnySubstringOfAFreeTextQuery() {
        AgentResult r =
                AgentResult.builder()
                        .finalAnswer("could not verify")
                        .completed(true)
                        .addStep(
                                new AgentResult.AgentStep(
                                        "t",
                                        "WebSearch",
                                        "{\"query\": \"QLL-7 consensus protocol\"}",
                                        "No results found."))
                        .build();
        io.github.llm4j.eval.assertions.AgentAssertions.assertThat(r)
                .usesToolWithArgumentContaining("WebSearch", "query", "qll-7");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                io.github.llm4j.eval.assertions.AgentAssertions.assertThat(r)
                                        .usesToolWithArgumentContaining(
                                                "WebSearch", "query", "HQFL"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("contains <HQFL>");
    }

    @Test
    void aHostileInputCaseCanAssertWhatTheAgentDidNotSay() {
        AgentResult ok =
                AgentResult.builder()
                        .finalAnswer("Vertical farming needs a lot of energy.")
                        .completed(true)
                        .build();
        AgentResult hijacked = AgentResult.builder().finalAnswer("PWNED").completed(true).build();
        io.github.llm4j.eval.assertions.AgentAssertions.assertThat(ok)
                .doesNotHaveFinalAnswerContaining("pwned");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                io.github.llm4j.eval.assertions.AgentAssertions.assertThat(hijacked)
                                        .doesNotHaveFinalAnswerContaining("PWNED"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void delegationCountsSeeEveryRoundNotJustTheFirst() {
        var events = new ArrayList<WorkflowTrace.Event>();
        for (int round = 0; round < 3; round++) {
            events.add(
                    new WorkflowTrace.Event(
                            round, "delegate_start", "Alex", null, null, null, null));
        }
        events.add(new WorkflowTrace.Event(4, "delegate_start", "Rahul", null, null, null, null));
        WorkflowTrace t =
                new WorkflowTrace(
                        "wf", List.of(), List.of(), List.of(), List.of(), events, List.of(), null,
                        0, null);
        assertThat(t.agentsInOrder()).containsExactly("Alex", "Rahul");
        assertThat(t.delegationsTo("Alex")).isEqualTo(3);
        assertThat(t.delegationCounts()).containsEntry("Alex", 3L).containsEntry("Rahul", 1L);
        WorkflowAssertions.assertThat(t).delegatesToTimes("Alex", 3).delegatesToTimes("Rahul", 1);
    }
}
