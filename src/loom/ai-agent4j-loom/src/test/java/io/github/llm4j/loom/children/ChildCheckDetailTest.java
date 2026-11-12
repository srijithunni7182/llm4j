package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.ScriptValidator;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 33a: the details of the child checks that decide an error from a warning, and say what to do. */
class ChildCheckDetailTest {

    private static final String BASE =
            """
            tool Calc { use: calculator }
            agent Researcher { model: "m" system: "s" steerable: true streaming: true tools: [Calc] }
            agent Quiet { model: "m" system: "s" }
            evaluator J { model: "m" }
            workflow Inner(x) { delegate "go" to Quiet -> q  return q }
            workflow Research(topic) {
                delegate "Find {topic}" to Researcher -> notes
                checkpoint collected
                run Calc() -> c
                return notes
            }
            workflow Chain(topic) {
                call Inner(x = topic) -> r
                return r
            }
            """;

    private static List<ScriptValidator.Problem> check(String all) {
        var script = StartParseTest.parse(all);
        return new ScriptValidator().validate(script, new ScriptValidator.Context().registeredTools(java.util.Set.of()).baseDir(Path.of(".")));
    }

    private static List<ScriptValidator.Problem> main(String body) {
        return check(BASE + "workflow Main(q) {\n" + body + "\n}");
    }

    private static List<String> errors(List<ScriptValidator.Problem> p) {
        return p.stream().filter(x -> x.severity() == ScriptValidator.Severity.ERROR).map(ScriptValidator.Problem::message).toList();
    }

    private static List<String> warnings(List<ScriptValidator.Problem> p) {
        return p.stream().filter(x -> x.severity() == ScriptValidator.Severity.WARNING).map(ScriptValidator.Problem::message).toList();
    }

    private static final String START = "start Research(topic = q) budget { tokens: 10 } -> work\n";

    @Test
    void steeringSomethingThatIsNotAHandleIsAnError() {
        assertThat(errors(main("steer nothing Researcher \"x\""))).anyMatch(e -> e.contains("nothing") && e.contains("not a handle"));
    }

    @Test
    void aStartThatOnlyWaitsIsNotReportedAsNeverUsed() {
        assertThat(warnings(main(START + "wait work timeout 1m -> r"))).noneMatch(w -> w.contains("never waited"));
        assertThat(warnings(main(START + "cancel work"))).noneMatch(w -> w.contains("never waited"));
    }

    @Test
    void aChildThatSleepsExactlyAsLongAsItsDeadlineIsNotWarnedAboutButOneThatSleepsLongerIs() {
        String child = "workflow Nap(x) { sleep 10m  note \"x\" }\n";
        assertThat(warnings(check(child + "workflow Main(q) { start Nap(x = q) timeout 10m budget { tokens: 1 } -> w  wait w timeout 1m }")))
                .noneMatch(w -> w.contains("sleeps"));
        assertThat(warnings(check(child + "workflow Main(q) { start Nap(x = q) timeout 9m budget { tokens: 1 } -> w  wait w timeout 1m }")))
                .anyMatch(w -> w.contains("sleeps"));
    }

    @Test
    void theJudgeFloorIsThirtySecondsExactlyAndTheMessageSaysHowManySecondsWereWritten() {
        String rule = START + "supervise work budget { tokens: 5 } { assert \"x\" using J every %s max 1 { wrap_up } }\nwait work timeout 1m";
        assertThat(errors(main(rule.formatted("30s")))).noneMatch(e -> e.contains("30 seconds"));
        assertThat(errors(main(rule.formatted("10s")))).anyMatch(e -> e.contains("every 10s"));
    }

    @Test
    void aHandleKeyedInTextIsReportedWithItsKeyAndAHandleGivenToATaskOrToolNamesTheConstruct() {
        assertThat(errors(main("start Research(topic = q) budget { tokens: 1 } -> work[\"a\"]\nnote \"{work[a]}\"\nwait all work timeout 1m")))
                .anyMatch(e -> e.contains("{work[a]}"));
        var problems = main(START + "use Calc(item = work) -> y\nwait work timeout 1m");
        assertThat(problems).anyMatch(p -> p.severity() == ScriptValidator.Severity.ERROR && "use".equals(p.construct()));
        var runProblems = main(START + "run Calc(item = work) -> y\nwait work timeout 1m");
        assertThat(runProblems).anyMatch(p -> p.severity() == ScriptValidator.Severity.ERROR && "run".equals(p.construct()));
    }

    @Test
    void aFactOfAHandleMayBePassedToATaskButTheHandleItselfMayNot() {
        assertThat(errors(main(START + "run Calc(item = work.status) -> y\nwait work timeout 1m"))).noneMatch(e -> e.contains("cannot be given"));
        assertThat(errors(main(START + "run Calc(item = work) -> y\nwait work timeout 1m"))).anyMatch(e -> e.contains("cannot be given"));
    }

    @Test
    void aLoopWithItsMaxMayStartChildren() {
        assertThat(errors(main("loop until (q == \"x\") max 3 {\n start Research(topic = q) budget { tokens: 1 } -> work[q]\n}\nwait all work timeout 1m")))
                .noneMatch(e -> e.contains("needs the loop's max"));
    }

    @Test
    void aStatusTestGuardsAResultReadAndAPartialReadIsNotGuardedByAnythingButIsOk() {
        assertThat(warnings(main(START + "alt (work.status == \"completed\") { note \"{work.result}\" }\nwait work timeout 1m")))
                .noneMatch(w -> w.contains("work.result"));
        assertThat(warnings(main(START + "wait work timeout 1m\nnote \"{work.partial}\"")))
                .anyMatch(w -> w.contains("work.partial"));
        assertThat(warnings(main(START + "wait work timeout 1m\nnote \"{work.reason}\"")))
                .noneMatch(w -> w.contains("work.reason"));
    }

    @Test
    void everyNameOfAnAssertionIsCheckedAndAgentsToolsAndTasksAreKnown() {
        String s = START + "supervise work { assert calls Ghost before Phantom { wrap_up } }\nwait work timeout 1m";
        assertThat(errors(main(s))).anyMatch(e -> e.contains("\"Ghost\"")).anyMatch(e -> e.contains("\"Phantom\""));
        assertThat(errors(main(START + "supervise work { assert never calls Researcher { cancel }  assert never calls Calc { cancel } }\nwait work timeout 1m")))
                .noneMatch(e -> e.contains("not a tool, agent or task"));
    }

    @Test
    void anAssertionOnANameTheChildNeverUsesIsWarnedAboutAndOneItUsesThroughAnAgentOrACalledWorkflowIsNot() {
        assertThat(warnings(main(START + "supervise work { assert never calls Quiet { cancel } }\nwait work timeout 1m")))
                .anyMatch(w -> w.contains("never uses \"Quiet\""));
        assertThat(warnings(main(START + "supervise work { assert never calls Researcher { cancel } }\nwait work timeout 1m")))
                .noneMatch(w -> w.contains("never uses"));
        assertThat(warnings(main(START + "supervise work { assert never calls Calc { cancel } }\nwait work timeout 1m")))
                .noneMatch(w -> w.contains("never uses"));
        String viaCall = "start Chain(topic = q) budget { tokens: 1 } -> work\nsupervise work { assert never calls Quiet { cancel } }\nwait work timeout 1m";
        assertThat(warnings(main(viaCall))).noneMatch(w -> w.contains("never uses"));
    }

    @Test
    void aMissingCheckpointListsTheOnesTheChildHas() {
        assertThat(errors(main(START + "supervise work { assert reaches \"nowhere\" within 1m { wrap_up } }\nwait work timeout 1m")))
                .anyMatch(e -> e.contains("Its checkpoints: collected"));
    }

    @Test
    void aRewindOfAChildToACheckpointItDidNotDeclareIsRefusedAndListsTheOnesItHas() {
        var problems = main(START + "cancel work rewind to nowhere at most 2 times\nwait work timeout 1m");
        assertThat(errors(problems)).anyMatch(e -> e.contains("\"nowhere\"") && e.contains("collected"));
        assertThat(errors(problems)).noneMatch(e -> e.contains("not available yet"));
    }

    @Test
    void aRewindOfAChildToACheckpointItDeclaredPassesTheCheck() {
        assertThat(errors(main(START + "cancel work rewind to collected at most 2 times\nwait work timeout 1m")))
                .noneMatch(e -> e.contains("rewind") || e.contains("collected") || e.contains("cancel"));
    }

    // ---- F9: two warnings (phase 34a) ----

    private static final String SMALL = "start Research(topic = q) budget { tokens: 10 } -> ";

    @Test
    void aBlockThatStartsMoreChildrenThanMaxChildrenAllowsIsWarnedWhereItIsProvable() {
        String body = SMALL + "a\n" + SMALL + "b\n" + SMALL + "c\nwait all a timeout 1m";
        var three = check("max_children: 2\n" + BASE + "workflow Main(q) {\n" + body + "\n}");

        assertThat(warnings(three)).anyMatch(w -> w.contains("max_children") && w.contains("2") && w.contains("3"));
    }

    @Test
    void aWaitBetweenStartsFreesASlotSoNoWarningIsGiven() {
        String body = SMALL + "a\nwait a timeout 1m\n" + SMALL + "b\nwait b timeout 1m\n" + SMALL + "c\nwait c timeout 1m";
        var spread = check("max_children: 1\n" + BASE + "workflow Main(q) {\n" + body + "\n}");

        assertThat(warnings(spread)).noneMatch(w -> w.contains("max_children"));
    }

    @Test
    void aBoundedLoopThatStartsMoreChildrenThanTheLimitWithNothingWaitedOnIsWarned() {
        String loop = "for each t in q.topics max 5 {\nstart Research(topic = t.name) budget { tokens: 10 } -> work[t.name]\n}\nwait all work timeout 1m";
        assertThat(warnings(check("max_children: 3\n" + BASE + "workflow Main(q) {\n" + loop + "\n}")))
                .anyMatch(w -> w.contains("max_children") && w.contains("5") && w.contains("3"));
        assertThat(warnings(check("max_children: 5\n" + BASE + "workflow Main(q) {\n" + loop + "\n}")))
                .noneMatch(w -> w.contains("max_children"));
    }

    @Test
    void aLoopThatWaitsInsideItsBodyDoesNotExceedTheLimitAtOnce() {
        String loop = "for each t in q.topics max 5 {\nstart Research(topic = t.name) budget { tokens: 10 } -> work[t.name]\nwait work[t.name] timeout 1m\n}";
        assertThat(warnings(check("max_children: 1\n" + BASE + "workflow Main(q) {\n" + loop + "\n}")))
                .noneMatch(w -> w.contains("max_children"));
    }

    @Test
    void theDefaultLimitOfEightCountsWhenTheScriptSetsNone() {
        String loop = "for each t in q.topics max 9 {\nstart Research(topic = t.name) budget { tokens: 10 } -> work[t.name]\n}\nwait all work timeout 1m";
        assertThat(warnings(check(BASE + "workflow Main(q) {\n" + loop + "\n}"))).anyMatch(w -> w.contains("max_children") && w.contains("8"));
    }

    @Test
    void twoSteersToOneAgentOfOneChildInOneBlockAreWarnedAboutBecauseTheLaterOverridesTheEarlier() {
        var two = main(START + "steer work Researcher \"narrow it\"\nsteer work Researcher \"widen it\"\nwait work timeout 1m");
        var different = main(START + "steer work Researcher \"narrow it\"\nrun Calc() -> c\nwait work timeout 1m");

        assertThat(warnings(two)).anyMatch(w -> w.contains("steer") && w.contains("Researcher") && w.contains("one block"));
        assertThat(warnings(different)).noneMatch(w -> w.contains("steer") && w.contains("one block"));
    }

    @Test
    void twoSteersInOneRulesActionsAreWarnedAboutBecauseOnlyOneIsDeliveredATick() {
        var twice = main(START + "supervise work { when tokens > 5 { steer \"a\"  steer \"b\" } }\nwait work timeout 1m");
        var once = main(START + "supervise work { when tokens > 5 { steer \"a\" } }\nwait work timeout 1m");

        assertThat(warnings(twice)).anyMatch(w -> w.contains("one steer") && w.contains("tick"));
        assertThat(warnings(once)).noneMatch(w -> w.contains("one steer"));
    }

    // ---- F12, guard G26: the judge's budget is paired with its start ----

    private static final String JUDGED =
            "supervise work budget { tokens: 5 } { assert \"on topic\" using J every 2m max 2 { wrap_up } }\n";

    @Test
    void aSuperviseOfAHandleStartedInTheSameBlockIsPairedAndNeedsNoWarning() {
        assertThat(warnings(main(START + JUDGED + "wait work timeout 1m"))).noneMatch(w -> w.contains("unsupervised"));
    }

    @Test
    void aJudgedSuperviseThatCannotBePairedWithItsStartIsWarnedAboutBecauseItReservesLate() {
        String source =
                BASE
                        + "workflow Helper(q) {\n"
                        + START
                        + "wait work timeout 1m\n}\n"
                        + "workflow Main(q) {\n"
                        + "start Research(topic = q) budget { tokens: 10 } -> work\nalt (q == \"x\") {\n"
                        + JUDGED
                        + "}\nwait work timeout 1m\n}";

        assertThat(warnings(check(source)))
                .anyMatch(w -> w.contains("already running") && w.contains("unsupervised") && w.contains("same block"));
    }
}
