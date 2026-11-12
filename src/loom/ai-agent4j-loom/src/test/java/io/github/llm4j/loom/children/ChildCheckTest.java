package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.ScriptValidator;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 33a: the load errors and the {@code weave check} errors and warnings for children. */
class ChildCheckTest {

    private static final String BASE =
            """
            agent Researcher { model: "m" system: "s" steerable: true streaming: true }
            agent Quiet { model: "m" system: "s" }
            evaluator TopicJudge { model: "m" }
            workflow Research(topic) {
                delegate "Find {topic}" to Researcher -> notes
                checkpoint collected
                return notes
            }
            """;

    private static List<ScriptValidator.Problem> check(String main) {
        var script = StartParseTest.parse(BASE + "workflow Main(q) {\n" + main + "\n}");
        var context =
                new ScriptValidator.Context().registeredTools(java.util.Set.of()).baseDir(Path.of("."));
        return new ScriptValidator().validate(script, context);
    }

    private static List<String> errors(String main) {
        return check(main).stream()
                .filter(p -> p.severity() == ScriptValidator.Severity.ERROR)
                .map(ScriptValidator.Problem::message)
                .toList();
    }

    private static List<String> warnings(String main) {
        return check(main).stream()
                .filter(p -> p.severity() == ScriptValidator.Severity.WARNING)
                .map(ScriptValidator.Problem::message)
                .toList();
    }

    private static final String GOOD =
            """
            start Research(topic = q) timeout 20m budget { tokens: 1000 } -> work
            supervise work { when tokens > 900 { cancel use partial } }
            wait work timeout 25m -> result
            """;

    @Test
    void aWellFormedSupervisorHasNoErrorsAndNoWarnings() {
        assertThat(errors(GOOD)).isEmpty();
        assertThat(warnings(GOOD)).isEmpty();
    }

    @Test
    void aStartWithoutABudgetIsAnError() {
        assertThat(errors("start Research(topic = q) -> work\nwait work timeout 5m -> r"))
                .anyMatch(e -> e.contains("budget") && e.contains("start"));
    }

    @Test
    void aWaitWithoutATimeoutIsAnError() {
        assertThat(
                        errors(
                                "start Research(topic = q) budget { tokens: 10 } -> work\nwait work -> r"))
                .anyMatch(e -> e.contains("timeout") && e.contains("wait"));
    }

    @Test
    void startingAnUnknownWorkflowIsAnError() {
        assertThat(errors("start Nobody(topic = q) budget { tokens: 10 } -> work\nwait work timeout 1m"))
                .anyMatch(e -> e.contains("Nobody") && e.contains("workflow"));
    }

    @Test
    void aStartInALoopWithNoBoundIsAnError() {
        String unbounded =
                """
                for each t in q.topics {
                    start Research(topic = t) budget { tokens: 10 } -> work[t]
                }
                wait all work timeout 1m -> rs
                """;
        assertThat(errors(unbounded)).anyMatch(e -> e.contains("start") && e.contains("max"));
        String bounded = unbounded.replace("in q.topics {", "in q.topics max 3 {");
        assertThat(errors(bounded)).noneMatch(e -> e.contains("start") && e.contains("max"));
    }

    @Test
    void waitingForOrCancellingSomethingThatIsNotAHandleIsAnError() {
        assertThat(errors("wait nothing timeout 1m -> r")).anyMatch(e -> e.contains("nothing") && e.contains("start"));
        assertThat(errors("cancel nothing")).anyMatch(e -> e.contains("nothing") && e.contains("start"));
        assertThat(errors("supervise nothing { when tokens > 1 { cancel } }"))
                .anyMatch(e -> e.contains("nothing") && e.contains("start"));
    }

    @Test
    void twoSuperviseBlocksForOneHandleIsAnError() {
        String two =
                GOOD + "supervise work { when calls > 3 { wrap_up } }\n";
        assertThat(errors(two)).anyMatch(e -> e.contains("one supervise block") && e.contains("work"));
    }

    @Test
    void aHandleCannotBeWrittenIntoText() {
        assertThat(errors(GOOD + "note \"the child is {work}\""))
                .anyMatch(e -> e.contains("handle") && e.contains("work"));
        assertThat(errors(GOOD + "delegate \"Look at {work}\" to Quiet -> x"))
                .anyMatch(e -> e.contains("handle") && e.contains("work"));
    }

    @Test
    void aHandleCannotBeGivenToATaskOrReturned() {
        assertThat(errors(GOOD + "run Tool(item = work) -> y"))
                .anyMatch(e -> e.contains("handle") && e.contains("work"));
        assertThat(errors(GOOD + "return work")).anyMatch(e -> e.contains("handle") && e.contains("return"));
    }

    @Test
    void theFactsOfAHandleMayBeWrittenIntoText() {
        assertThat(errors(GOOD + "note \"status: {work.status}\"")).isEmpty();
    }

    @Test
    void steeringAnAgentThatIsNotSteerableIsAnErrorThatSaysWhy() {
        assertThat(errors(GOOD + "steer work Quiet \"hello\""))
                .anyMatch(e -> e.contains("Quiet") && e.contains("steerable"));
        assertThat(errors(GOOD + "steer work Researcher \"hello\"")).isEmpty();
        assertThat(errors(GOOD + "steer work Missing \"hello\""))
                .anyMatch(e -> e.contains("Missing") && e.contains("agent"));
    }

    @Test
    void aJudgedAssertionNeedsItsOwnBudgetAnEvaluatorAndACadenceOfThirtySeconds() {
        String judged = "supervise work { assert \"on topic\" using TopicJudge every 2m max 2 { wrap_up } }";
        String start = "start Research(topic = q) budget { tokens: 10 } -> work\nwait work timeout 1m -> r\n";
        assertThat(errors(start + judged)).anyMatch(e -> e.contains("judge") && e.contains("budget"));
        assertThat(errors(start + judged.replace("supervise work {", "supervise work budget { tokens: 5 } {")))
                .isEmpty();
        assertThat(
                        errors(
                                start
                                        + judged.replace("supervise work {", "supervise work budget { tokens: 5 } {")
                                                .replace("using TopicJudge", "using Nobody")))
                .anyMatch(e -> e.contains("Nobody") && e.contains("evaluator"));
        assertThat(
                        errors(
                                start
                                        + judged.replace("supervise work {", "supervise work budget { tokens: 5 } {")
                                                .replace("every 2m", "every 10s")))
                .anyMatch(e -> e.contains("30 seconds"));
    }

    @Test
    void anAssertionNamesWhatTheChildScriptCanDo() {
        String start = "start Research(topic = q) budget { tokens: 10 } -> work\nwait work timeout 1m -> r\n";
        assertThat(errors(start + "supervise work { assert reaches \"nowhere\" within 1m { wrap_up } }"))
                .anyMatch(e -> e.contains("nowhere") && e.contains("checkpoint"));
        assertThat(errors(start + "supervise work { assert reaches \"collected\" within 1m { wrap_up } }"))
                .isEmpty();
        assertThat(errors(start + "supervise work { assert never calls Ghost { cancel } }"))
                .anyMatch(e -> e.contains("Ghost"));
    }

    @Test
    void aStartThatIsNeverWaitedOnCancelledOrSupervisedIsAWarning() {
        assertThat(warnings("start Research(topic = q) budget { tokens: 10 } -> work"))
                .anyMatch(w -> w.contains("work") && w.contains("never"));
    }

    @Test
    void aResultReadWithoutACheckIsAWarning() {
        assertThat(warnings(GOOD + "note \"{work.result}\""))
                .anyMatch(w -> w.contains("work.result") && w.contains("is ok"));
        assertThat(warnings(GOOD + "alt (work is ok) { note \"{work.result}\" }"))
                .noneMatch(w -> w.contains("work.result"));
    }

    @Test
    void aChildThatSleepsLongerThanItsDeadlineIsAWarning() {
        var script =
                StartParseTest.parse(
                        """
                        agent A { model: "m" system: "s" }
                        workflow Slow(x) { sleep 2h  note "done" }
                        workflow Main(q) {
                            start Slow(x = q) timeout 10m budget { tokens: 10 } -> work
                            wait work timeout 1m -> r
                        }
                        """);
        var context =
                new ScriptValidator.Context().registeredTools(java.util.Set.of()).baseDir(Path.of("."));
        assertThat(new ScriptValidator().validate(script, context))
                .anyMatch(p -> p.severity() == ScriptValidator.Severity.WARNING && p.message().contains("sleep"));
    }

    @Test
    void aWorkflowThatStartsItselfIsAWarning() {
        var script =
                StartParseTest.parse(
                        """
                        workflow Loop(x) {
                            start Loop(x = x) budget { tokens: 10 } -> me
                            wait me timeout 1m -> r
                        }
                        """);
        var context =
                new ScriptValidator.Context().registeredTools(java.util.Set.of()).baseDir(Path.of("."));
        assertThat(new ScriptValidator().validate(script, context))
                .anyMatch(p -> p.severity() == ScriptValidator.Severity.WARNING && p.message().contains("itself"));
    }

    @Test
    void aCostRuleNeedsAPriceTableAndSaysSo() {
        assertThat(warnings(GOOD.replace("tokens > 900", "cost > \"$0.50\"")))
                .anyMatch(w -> w.contains("cost") && w.contains("price table"));
    }

    @Test
    void isOkAcceptsAHandleAndAKeyedHandle() {
        assertThat(errors(GOOD + "alt (work is ok) { note \"a\" }")).isEmpty();
        String keyed =
                """
                for each t in q.topics max 2 {
                    start Research(topic = t) budget { tokens: 10 } -> work[t]
                }
                wait all work timeout 1m -> rs
                alt (work["a"] is ok) { note "a" }
                """;
        assertThat(errors(keyed)).isEmpty();
    }
}
