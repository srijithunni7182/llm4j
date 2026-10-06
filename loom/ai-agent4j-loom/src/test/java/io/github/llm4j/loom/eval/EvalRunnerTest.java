package io.github.llm4j.loom.eval;

import static io.github.llm4j.loom.eval.EvalTestSupport.executor;
import static io.github.llm4j.loom.eval.EvalTestSupport.finalAnswer;
import static io.github.llm4j.loom.eval.EvalTestSupport.scenario;
import static io.github.llm4j.loom.eval.EvalTestSupport.useTool;
import static io.github.llm4j.loom.eval.EvalTestSupport.with;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.judge.JudgeVerdict;
import io.github.llm4j.loom.eval.EvalTestSupport.Models;
import io.github.llm4j.loom.execution.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R3.2 to R3.5 of loom-weave-eval: what each kind of check does with what a run produced. */
class EvalRunnerTest {

    private static final String SCRIPT = """
            agent Helper { model: "m-help" system: "You help." tools: [Lookup] }
            workflow Main(topic) {
                delegate "Research {topic}" to Helper -> findings
                note "done"
            }
            """;

    private final List<Map<String, Object>> lookups = new ArrayList<>();

    private ToolRegistry tools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("Lookup", new Tool() {
            @Override public String getName() { return "Lookup"; }
            @Override public String getDescription() { return "looks things up"; }
            @Override public String execute(Map<String, Object> args) { lookups.add(args); return "found it"; }
        });
        return registry;
    }

    private EvalRunner runner(Models models, RubricJudge judge) {
        return new EvalRunner(before -> executor(SCRIPT, models, tools(), before), judge == null ? null : model -> judge, name -> "m-help");
    }

    private static Models answering(String answer) {
        return new Models((system, n) -> finalAnswer(answer));
    }

    private static ScenarioResult.Check only(ScenarioResult r) {
        assertThat(r.checks()).hasSize(1);
        return r.checks().get(0);
    }

    // ── an agent ─────────────────────────────────────────────────────────────

    @Test
    void theInputIsTheAgentsTaskAndAMatchingAnswerPasses() {
        Models models = answering("The answer is 36.");

        ScenarioResult r = runner(models, null).agent("helper.yaml", "Helper", with(scenario("n", "What is 15% of 240?"), "36", null, null, null));

        assertThat(r.status()).isEqualTo(Status.PASS);
        assertThat(only(r).kind()).isEqualTo("answer contains");
        assertThat(models.systems.get(0)).contains("You help.");
        assertThat(r.target()).isEqualTo("Helper");
        assertThat(r.kind()).isEqualTo("agent");
    }

    @Test
    void aWrongAnswerFailsAndSaysWhatTheAnswerWas() {
        ScenarioResult r = runner(answering("It is 35."), null).agent("f", "Helper", with(scenario("n", "q"), "36", null, null, null));

        assertThat(r.status()).isEqualTo(Status.FAIL);
        assertThat(only(r).detail()).isEqualTo("the answer was: It is 35.");
    }

    @Test
    void containsIgnoresCaseAndAnExactAnswerIsComparedWithoutSurroundingSpace() {
        EvalScenario exact = new EvalScenario("n", "q", null, "  Paris ", null, null, null, null, null, null);

        assertThat(runner(answering("PARIS is the capital"), null).agent("f", "Helper", with(scenario("n", "q"), "paris", null, null, null)).status()).isEqualTo(Status.PASS);
        assertThat(runner(answering("Paris"), null).agent("f", "Helper", exact).status()).isEqualTo(Status.PASS);
        assertThat(runner(answering("Paris, France"), null).agent("f", "Helper", exact).status()).isEqualTo(Status.FAIL);
    }

    @Test
    void expectedToolsMustHaveBeenUsedAndAMissingOneIsNamed() {
        Models used = new Models((system, n) -> n == 0 ? useTool("Lookup", "{\"q\": \"x\"}") : finalAnswer("done"));

        ScenarioResult ok = runner(used, null).agent("f", "Helper", with(scenario("n", "q"), null, List.of("Lookup"), null, null));
        ScenarioResult missed = runner(answering("no tool"), null).agent("f", "Helper", with(scenario("n", "q"), null, List.of("Lookup"), null, null));

        assertThat(ok.status()).isEqualTo(Status.PASS);
        assertThat(lookups).hasSize(1);
        assertThat(missed.status()).isEqualTo(Status.FAIL);
        assertThat(only(missed).detail()).isEqualTo("not used: Lookup; used: none");
    }

    @Test
    void aRubricLineIsJudgedAndPassesAtTheThreshold() {
        RubricJudge judge = (criteria, subject, s) -> new JudgeVerdict(criteria.contains("kind") ? 0.9 : 0.3, "because");

        ScenarioResult r = runner(answering("hello"), judge).agent("f", "Helper", with(scenario("n", "q"), null, null, List.of("Is kind", "Is brief"), null));

        assertThat(r.status()).isEqualTo(Status.FAIL);
        assertThat(r.checks()).extracting(ScenarioResult.Check::status).containsExactly(Status.PASS, Status.FAIL);
        assertThat(r.checks().get(1).detail()).isEqualTo("score 0.30: because");
    }

    @Test
    void theJudgeIsShownTheAnswerAndTheScenario() {
        List<String> seen = new ArrayList<>();
        RubricJudge judge = (criteria, subject, s) -> {
            seen.add(criteria + "|" + subject + "|" + s.input());
            return new JudgeVerdict(1.0, "ok");
        };

        runner(answering("the answer"), judge).agent("f", "Helper", with(scenario("n", "the question"), null, null, List.of("Is right"), null));

        assertThat(seen).containsExactly("Is right|the answer|the question");
    }

    @Test
    void withNoJudgeARubricLineIsUnjudgedNeverAPass() {
        ScenarioResult r = runner(answering("hello"), null).agent("f", "Helper", with(scenario("n", "q"), null, null, List.of("Is kind"), null));

        assertThat(r.status()).isEqualTo(Status.UNJUDGED);
        assertThat(only(r).status()).isEqualTo(Status.UNJUDGED);
        assertThat(only(r).detail()).isEqualTo("no judge ran");
    }

    @Test
    void aJudgeThatFailsLeavesTheLineUnjudgedNotFailedOrPassed() {
        RubricJudge broken = (criteria, subject, s) -> {
            throw new IllegalStateException("the judge model is down");
        };

        ScenarioResult r = runner(answering("hello"), broken).agent("f", "Helper", with(scenario("n", "q"), null, null, List.of("Is kind"), null));

        assertThat(r.status()).isEqualTo(Status.UNJUDGED);
        assertThat(only(r).detail()).isEqualTo("the judge failed: the judge model is down");
    }

    @Test
    void aFailureBeatsUnjudgedWhichBeatsAPass() {
        ScenarioResult r = runner(answering("It is 35."), null).agent("f", "Helper", with(scenario("n", "q"), "36", null, List.of("Is kind"), null));

        assertThat(r.checks()).extracting(ScenarioResult.Check::status).containsExactly(Status.FAIL, Status.UNJUDGED);
        assertThat(r.status()).isEqualTo(Status.FAIL);
        assertThat(Status.PASS.and(Status.UNJUDGED)).isEqualTo(Status.UNJUDGED);
    }

    @Test
    void aScenarioWithNothingToCheckIsUnjudgedAndSaysSo() {
        ScenarioResult r = runner(answering("hello"), null).agent("f", "Helper", scenario("n", "q"));

        assertThat(r.status()).isEqualTo(Status.UNJUDGED);
        assertThat(only(r).kind()).isEqualTo("nothing to check");
    }

    @Test
    void aRunThatBreaksIsAFailureWithTheReason() {
        EvalRunner runner = new EvalRunner(before -> {
            throw new IllegalStateException("cannot start");
        }, null, name -> "m");

        ScenarioResult r = runner.agent("f", "Helper", with(scenario("n", "q"), "x", null, null, null));

        assertThat(r.status()).isEqualTo(Status.FAIL);
        assertThat(r.error()).isEqualTo("cannot start");
    }

    @Test
    void anAgentThatDoesNotExistIsAFailureNotACrash() {
        ScenarioResult r = runner(answering("x"), null).agent("f", "Nobody", with(scenario("n", "q"), "x", null, null, null));

        assertThat(r.status()).isEqualTo(Status.FAIL);
        assertThat(r.error()).contains("Agent not found: Nobody");
    }

    @Test
    void theJudgeIsChosenByTheAgentsModelByDefault() {
        List<String> asked = new ArrayList<>();
        EvalRunner runner = new EvalRunner(before -> executor(SCRIPT, answering("x"), tools(), before), model -> {
            asked.add(model);
            return (c, s, sc) -> new JudgeVerdict(1.0, "ok");
        }, name -> "the-agents-model");

        runner.agent("f", "Helper", with(scenario("n", "q"), null, null, List.of("Is fine"), null));

        assertThat(asked).containsExactly("the-agents-model");
    }

    // ── a workflow ───────────────────────────────────────────────────────────

    @Test
    void aWorkflowRunsWithTheInputAsItsFirstParameterAndItsOutputIsWhatTheLastAgentStepMade() {
        Models models = answering("Composting is great");

        ScenarioResult r = runner(models, null).workflow("workflow.yaml", "Main", "topic", with(scenario("n", "home composting"), "composting", null, null, null), "m");

        assertThat(r.status()).isEqualTo(Status.PASS);
        assertThat(r.kind()).isEqualTo("workflow");
        assertThat(models.systems).isNotEmpty();
    }

    @Test
    void anExpectLineIsJudgedAgainstAnAccountOfTheStepsInOrder() {
        List<String> subjects = new ArrayList<>();
        RubricJudge judge = (criteria, subject, s) -> {
            subjects.add(subject);
            return new JudgeVerdict(0.95, "yes");
        };
        Models models = new Models((system, n) -> n == 0 ? useTool("Lookup", "{\"q\": \"x\"}") : finalAnswer("the findings"));

        ScenarioResult r = runner(models, judge).workflow("w", "Main", "topic", with(scenario("n", "t"), null, null, null, List.of("The lookup ran before the answer")), "m-help");

        assertThat(r.status()).isEqualTo(Status.PASS);
        assertThat(only(r).kind()).isEqualTo("expect");
        assertThat(subjects.get(0)).contains("1. delegate start [Helper]").contains("action [Helper]: Lookup").contains("delegate end [Helper]: the findings");
    }

    @Test
    void aWorkflowsToolsAreThoseItsAgentsCalled() {
        Models models = new Models((system, n) -> n == 0 ? useTool("Lookup", "{\"q\": \"x\"}") : finalAnswer("x"));

        ScenarioResult r = runner(models, null).workflow("w", "Main", "topic", with(scenario("n", "t"), null, List.of("Lookup"), null, null), "m");

        assertThat(r.error()).isNull();
        assertThat(r.checks()).as(r.toString()).isNotEmpty();
        assertThat(r.status()).as(r.toString()).isEqualTo(Status.PASS);
        assertThat(only(r).kind()).isEqualTo("tools used");
    }

    @Test
    void aWorkflowThatDoesNotExistFailsWithTheReason() {
        ScenarioResult r = runner(answering("x"), null).workflow("w", "Nope", "topic", with(scenario("n", "t"), "x", null, null, null), "m");

        assertThat(r.status()).isEqualTo(Status.FAIL);
        assertThat(r.error()).contains("Workflow not found: Nope");
    }

    @Test
    void theAccountIsShortenedAndNeverLongerThanItsLimit() {
        List<io.github.llm4j.loom.execution.TraceEvent> events = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            events.add(new io.github.llm4j.loom.execution.TraceEvent("delegate_start", "A", "s", "x".repeat(500), Map.of(), java.time.Instant.EPOCH));
        }
        events.add(new io.github.llm4j.loom.execution.TraceEvent("thought", "A", "s", "not shown", Map.of(), java.time.Instant.EPOCH));

        String account = EvalRunner.account(events);

        assertThat(account).contains("60. delegate start").contains("more steps not shown").doesNotContain("61.").doesNotContain("not shown\n1.").doesNotContain("thought");
        assertThat(account.lines().filter(l -> l.startsWith("1.")).findFirst().orElseThrow().length()).isLessThan(200);
        assertThat(EvalRunner.account(List.of())).isEqualTo("(no steps ran)");
    }

    // ── a mock run ───────────────────────────────────────────────────────────

    private EvalRunner mockRunner() {
        return new EvalRunner(before -> executor(SCRIPT, new MockModels(), tools(), before), null, name -> "m", true);
    }

    @Test
    void inAMockRunContentChecksAreUnjudgedBecauseAMockSaysNothingAboutContent() {
        EvalScenario s = new EvalScenario("n", "q", "36", "36", List.of("Lookup"), null, null, null, null, null, List.of("Is kind"), null);

        ScenarioResult r = mockRunner().agent("f", "Helper", s);

        assertThat(r.status()).isEqualTo(Status.UNJUDGED);
        assertThat(r.checks()).extracting(ScenarioResult.Check::status).containsOnly(Status.UNJUDGED);
        assertThat(r.checks()).extracting(ScenarioResult.Check::kind).containsExactly("answer contains", "answer is", "tools used", "rubric");
        assertThat(r.checks().get(0).detail()).isEqualTo("a mock run does not check content");
    }

    @Test
    void aMockWorkflowRunCompletesAndABrokenOneStillFails() {
        ScenarioResult ok = mockRunner().workflow("w", "Main", "topic", with(scenario("n", "t"), "anything", null, null, List.of("It ran")), "m");
        ScenarioResult broken = mockRunner().workflow("w", "Missing", "topic", with(scenario("n", "t"), "anything", null, null, null), "m");

        assertThat(ok.status()).isEqualTo(Status.UNJUDGED);
        assertThat(broken.status()).isEqualTo(Status.FAIL);
    }
}
