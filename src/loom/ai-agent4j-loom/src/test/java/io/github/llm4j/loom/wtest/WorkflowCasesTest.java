package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R5 to R8, R18 to R24 of spec 11, end to end: cases run a workflow with scripted models, and every
 * assertion has a case built to pass and a case built to fail with the message it must give.
 */
class WorkflowCasesTest {

    @TempDir Path dir;

    static final String ORDER =
            "  - name: order\n    input: { message: 'where is my parcel?' }\n    models:\n      Intent: { kind: order_status, urgent: 'no' }\n      Replier: 'Your parcel shipped yesterday.'\n";

    List<TestRunner.CaseResult> run(String... cases) throws Exception {
        return Wtest.run(dir, Wtest.TRIAGE, Wtest.triageCases(cases));
    }

    TestRunner.CaseResult only(String... cases) throws Exception {
        List<TestRunner.CaseResult> r = run(cases);
        assertThat(r).hasSize(1);
        return r.get(0);
    }

    @Test
    void aSmokeCaseAssertsOnlyThatTheRunCompleted() throws Exception {
        assertThat(only(ORDER).passed()).isTrue();
    }

    @Test
    void resultRouteCallsAndNeverAllPassWhenTheyHold() throws Exception {
        TestRunner.CaseResult r =
                only(
                        ORDER
                                + "    expect:\n      result: { contains: shipped }\n      route: [ classify Intent, delegate Replier, note ]\n      calls: { Replier: 1, Escalator: 0, Intent: 1 }\n      never: [ delegate Escalator ]\n      final: completed\n");
        assertThat(r.failures()).isEmpty();
        assertThat(r.passed()).isTrue();
    }

    @Test
    void everyAssertionKindFailsWhenItDoesNotHold() throws Exception {
        TestRunner.CaseResult r =
                only(
                        ORDER
                                + "    expect:\n      result: refunded\n      route: [ delegate Escalator ]\n      calls: { Replier: 2 }\n      never: [ delegate Replier ]\n      context: { intent.kind: refund }\n      models_used: { Replier: other-model }\n");
        assertThat(r.passed()).isFalse();
        assertThat(r.failures())
                .anyMatch(f -> f.startsWith("result: expected refunded"))
                .anyMatch(f -> f.startsWith("route: step 1 of the expected route did not happen"))
                .anyMatch(f -> f.startsWith("calls.Replier: expected 2, got 1"))
                .anyMatch(f -> f.startsWith("never: \"delegate Replier\" must not run"))
                .anyMatch(f -> f.startsWith("context.intent.kind: expected refund"))
                .anyMatch(f -> f.startsWith("models_used.Replier: expected other-model"));
    }

    @Test
    void allFailuresOfACaseAreReportedNotOnlyTheFirst() throws Exception {
        TestRunner.CaseResult r =
                only(
                        ORDER
                                + "    expect:\n      result: a\n      calls: { Replier: 5, Intent: 5 }\n");
        assertThat(r.failures()).hasSize(3);
    }

    @Test
    void theResultIsASubsetUnlessExact() throws Exception {
        String typed =
                "agent Auditor { model: \"m\"  system: \"s\"  output_schema: { status: string, score: number } }\n"
                        + "workflow Main(message) { delegate \"audit {message}\" to Auditor -> verdict\n return verdict }\n";
        String cases =
                "script: ../main.loom\nworkflow: Main\ncases:\n  - name: typed\n    input: { message: x }\n    models:\n      Auditor: { status: SECURE, score: 9 }\n    expect:\n      result: { status: SECURE, score: { gt: 5 } }\n  - name: exact fails\n    input: { message: x }\n    models:\n      Auditor: { status: SECURE, score: 9 }\n    expect:\n      exact: true\n      result: { status: SECURE }\n";
        List<TestRunner.CaseResult> r = Wtest.run(dir, typed, cases);
        assertThat(r.get(0).passed()).as(r.get(0).failures().toString()).isTrue();
        assertThat(r.get(1).failures())
                .singleElement()
                .asString()
                .contains("fields the test did not expect")
                .contains("score");
    }

    @Test
    void anUnscriptedCallFailsTheCaseAndNamesTheAgentTheCallAndTheTask() throws Exception {
        TestRunner.CaseResult r =
                only(
                        "  - name: no answer\n    input: { message: 'where is my parcel?' }\n    models:\n      Intent: { kind: order_status, urgent: 'no' }\n");
        assertThat(r.passed()).isFalse();
        assertThat(r.failures())
                .anyMatch(
                        f ->
                                f.contains("agent Replier has no scripted answer (call 1)")
                                        && f.contains("its task was: ")
                                        && f.contains("Reply to: where is my parcel?"));
    }

    @Test
    void anUnscriptedClassifierFailsTheCaseToo() throws Exception {
        TestRunner.CaseResult r = only("  - name: none\n    input: { message: hi }\n");
        assertThat(r.failures())
                .anyMatch(f -> f.contains("classifier Intent has no scripted answer"));
    }

    @Test
    void lenientAllowsANeutralAnswerAndSaysSo() throws Exception {
        TestRunner.Suite suite =
                Wtest.load(
                        dir,
                        Wtest.TRIAGE,
                        Wtest.triageCases(
                                "  - name: loose\n    input: { message: hi }\n    models:\n      Intent: { kind: order_status, urgent: 'no' }\n"));
        List<TestRunner.CaseResult> r = TestRunner.run(suite, null, true, false);
        assertThat(r.get(0).passed()).isTrue();
        assertThat(r.get(0).notes())
                .singleElement()
                .asString()
                .contains("agent Replier has no scripted answer");
    }

    @Test
    void aListAnswersCallsInOrderAndRunningOutIsAnErrorUnlessTheLastRepeats() throws Exception {
        String loop =
                "agent Writer { model: \"m\"  system: \"s\" }\n"
                        + "workflow Main(message) {\n loop until (draft == \"done\") max 4 {\n  delegate \"write {message}\" to Writer -> draft\n }\n return draft\n}\n";
        String ok =
                "script: ../main.loom\nworkflow: Main\ncases:\n  - name: three drafts\n    input: { message: x }\n    models:\n      Writer: [ one, two, done ]\n    expect:\n      calls: { Writer: 3 }\n      result: done\n";
        assertThat(Wtest.run(dir, loop, ok).get(0).passed()).isTrue();
        String short_ =
                "script: ../main.loom\nworkflow: Main\ncases:\n  - name: runs out\n    input: { message: x }\n    models:\n      Writer: [ one, two ]\n";
        TestRunner.CaseResult runsOut = Wtest.run(dir, loop, short_).get(0);
        assertThat(runsOut.failures())
                .anyMatch(
                        f ->
                                f.contains("more often than its list of 2 answers")
                                        && f.contains("(call 3)"));
        String repeat =
                short_.replace("    models:", "    repeat: last\n    models:")
                        .replace("Writer: [ one, two ]", "Writer: [ one, done ]");
        assertThat(Wtest.run(dir, loop, repeat).get(0).passed()).isTrue();
    }

    @Test
    void aWhenListChoosesTheAnswerByTheTaskTextAndNeedsAnOtherwise() throws Exception {
        String when =
                "script: ../main.loom\nworkflow: Main\ncases:\n  - name: by text\n    input: { message: 'I want a refund' }\n    models:\n      Intent: { kind: refund, urgent: 'no' }\n      Escalator:\n        when:\n          - { contains: refund, reply: 'refund case' }\n          - { otherwise: 'other case' }\n    expect:\n      result: refund case\n  - name: otherwise\n    input: { message: 'my parcel' }\n    models:\n      Intent: { kind: other, urgent: 'no' }\n      Escalator:\n        when:\n          - { contains: refund, reply: 'refund case' }\n          - { otherwise: 'other case' }\n    expect:\n      result: other case\n  - name: no otherwise\n    input: { message: 'my parcel' }\n    models:\n      Intent: { kind: other, urgent: 'no' }\n      Escalator:\n        when:\n          - { contains: refund, reply: 'refund case' }\n";
        List<TestRunner.CaseResult> r = Wtest.run(dir, Wtest.TRIAGE, when);
        assertThat(r.get(0).passed()).as(r.get(0).failures().toString()).isTrue();
        assertThat(r.get(1).passed()).as(r.get(1).failures().toString()).isTrue();
        assertThat(r.get(2).failures()).anyMatch(f -> f.contains("none of them matches the task"));
    }

    @Test
    void aClassifierThatEscalatesIsScriptedAsUnsure() throws Exception {
        String escalating =
                Wtest.TRIAGE.replace(
                        "ask urgent:", "confidence at least 0.8 else escalate\n    ask urgent:");
        String cases =
                "script: ../main.loom\nworkflow: Main\ncases:\n  - name: unsure\n    input: { message: hmm }\n    models:\n      Intent: { kind: unsure, urgent: 'no' }\n    expect:\n      final: suspended\n";
        TestRunner.CaseResult r = Wtest.run(dir, escalating, cases).get(0);
        // with nobody to ask, an escalated classification cannot complete: the case says what was
        // asked
        assertThat(r.failures())
                .anyMatch(f -> f.contains("a person was asked") || f.contains("ended"));
    }

    @Test
    void theFinalStateIsAssertedAndAFailedRunIsReportedWithItsReason() throws Exception {
        String boom =
                "script: ../main.loom\nworkflow: Main\ncases:\n  - name: no scripts\n    input: { message: hi }\n    expect:\n      final: failed\n";
        TestRunner.CaseResult r = Wtest.run(dir, Wtest.TRIAGE, boom).get(0);
        assertThat(r.failures()).noneMatch(f -> f.startsWith("expected the run to end"));
        assertThat(r.failures()).anyMatch(f -> f.contains("no scripted answer"));
    }

    @Test
    void casesShareNothing() throws Exception {
        List<TestRunner.CaseResult> r =
                run(
                        ORDER + "    expect: { calls: { Replier: 1 } }",
                        ORDER.replace("name: order", "name: again")
                                + "    expect: { calls: { Replier: 1 } }");
        assertThat(r).allMatch(TestRunner.CaseResult::passed);
    }

    @Test
    void aCaseNamePatternSelectsCases() throws Exception {
        TestRunner.Suite suite =
                Wtest.load(
                        dir,
                        Wtest.TRIAGE,
                        Wtest.triageCases(ORDER, ORDER.replace("name: order", "name: other")));
        assertThat(TestRunner.run(suite, "ord*", false, false))
                .extracting(TestRunner.CaseResult::name)
                .containsExactly("order");
        assertThat(TestRunner.run(suite, "*", false, false)).hasSize(2);
        assertThat(TestRunner.run(suite, "nothing", false, false)).isEmpty();
    }

    @Test
    void failFastStopsAtTheFirstFailure() throws Exception {
        TestRunner.Suite suite =
                Wtest.load(
                        dir,
                        Wtest.TRIAGE,
                        Wtest.triageCases(
                                ORDER + "    expect: { result: nope }",
                                ORDER.replace("name: order", "name: second")));
        assertThat(TestRunner.run(suite, null, false, true)).hasSize(1);
        assertThat(TestRunner.run(suite, null, false, false)).hasSize(2);
    }

    @Test
    void aMissingSecretIsNotAnErrorUnderTest() throws Exception {
        String keyed =
                "tool Hook { use: webhook  url: env.HOOK_URL }\n"
                        + "agent Sender { model: \"m\"  system: \"s\"  tools: [Hook] }\n"
                        + "workflow Main(message) { delegate \"say {message}\" to Sender -> r\n return r }\n";
        String cases =
                "script: ../main.loom\nworkflow: Main\ncases:\n  - name: no key needed\n    input: { message: hi }\n    models:\n      Sender: done\n    expect: { result: done }\n";
        TestRunner.CaseResult r = Wtest.run(dir, keyed, cases).get(0);
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void aClassifierGivenAListAnswersTheCallsInOrderAndTheLastOneRepeats() throws Exception {
        String script =
                """
                agent Replier { model: "gemini-2.5-flash"  system: "Answer." }
                intentclassifier Intent { model: "gemini-2.5-flash"  choose kind: order_status | refund }
                workflow Main(message) {
                    intentclassify Intent on message -> first
                    intentclassify Intent on message -> second
                    alt (first.kind == "refund") {
                        alt (second.kind == "order_status") { return "changed" } else { return "same" }
                    } else { return "no refund" }
                }
                """;
        String yaml =
                "script: ../main.loom\nworkflow: Main\ncases:\n  - name: the answer changes\n    input: { message: x }\n"
                        + "    models:\n      Intent: [ { kind: refund }, { kind: order_status } ]\n    expect:\n      result: changed\n";
        var results = Wtest.run(dir, script, yaml);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).failures()).isEmpty();
    }

    @Test
    void aRunThatEndsByHaltCanBeAssertedWithFinalHalted() throws Exception {
        String script =
                """
                workflow Main(message) {
                    halt "stopped on purpose"
                }
                """;
        String yaml =
                "script: ../main.loom\nworkflow: Main\ncases:\n  - name: halts\n    input: { message: x }\n    expect:\n      final: halted\n";
        var results = Wtest.run(dir, script, yaml);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).failures()).isEmpty();
    }
}
