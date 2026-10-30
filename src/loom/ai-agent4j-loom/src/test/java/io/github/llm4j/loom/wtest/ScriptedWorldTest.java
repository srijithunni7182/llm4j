package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R10 to R16 of spec 11: failures injected on a schedule, virtual time, scripted tools and tasks
 * with their effects recorded, and people who answer in order or leave the run suspended.
 */
class ScriptedWorldTest {

    @TempDir Path dir;

    static final String RETRY =
            """
            agent Replier { model: "m"  system: "Answer." }
            workflow Main(message) {
                delegate "Reply to {message}" to Replier -> reply retry 2 backoff 2s
                return reply
            }
            """;

    static final String HANDLED =
            """
            agent Replier { model: "m"  system: "Answer." }
            workflow Main(message) {
                delegate "Reply to {message}" to Replier -> reply retry 1 backoff 1s on_failure {
                    note "gave up"
                    return "fallback"
                }
                return reply
            }
            """;

    static String cases(String... body) {
        return "script: ../main.loom\nworkflow: Main\ncases:\n" + String.join("\n", body) + "\n";
    }

    static String in(String text) {
        return text.indent(4).stripTrailing() + "\n";
    }

    TestRunner.CaseResult one(String script, String yaml) throws Exception {
        List<TestRunner.CaseResult> r = Wtest.run(dir, script, yaml);
        assertThat(r).hasSize(1);
        return r.get(0);
    }

    // ---- failures and virtual time

    @Test
    void aStepFailingTwiceThenSucceedingRunsItsRetryForRealWithoutWaiting() throws Exception {
        long start = System.nanoTime();
        TestRunner.CaseResult r =
                one(
                        RETRY.replace("backoff 2s", "backoff 30s"),
                        cases(
                                "  - name: retried\n    input: { message: hi }\n    models: { Replier: 'back at last' }\n    fail: { Replier: { times: 2, category: timeout } }\n    expect:\n      calls: { Replier: 3 }\n      attempts: { Replier: 3 }\n      result: 'back at last'\n      elapsed_virtual: 90s\n"));
        assertThat(r.failures()).isEmpty();
        // 30 s + 60 s of backoff passed in virtual time; the case took a moment of real time
        assertThat((System.nanoTime() - start) / 1_000_000_000).isLessThan(20);
    }

    @Test
    void theRetriesRunOutAndTheRunFailsUnlessAnOnFailureTakesOver() throws Exception {
        TestRunner.CaseResult failed =
                one(
                        RETRY,
                        cases(
                                "  - name: exhausted\n    input: { message: hi }\n    models: { Replier: ok }\n    fail: { Replier: { always: true, category: server_error } }\n    expect:\n      final: failed\n      calls: { Replier: 3 }\n"));
        assertThat(failed.failures()).isEmpty();
        TestRunner.CaseResult handled =
                one(
                        HANDLED,
                        cases(
                                "  - name: handled\n    input: { message: hi }\n    models: { Replier: ok }\n    fail: { Replier: { always: true, category: timeout } }\n    expect:\n      final: completed\n      result: fallback\n      ran: [ on_failure of delegate Replier ]\n      calls: { Replier: 2 }\n"));
        assertThat(handled.failures()).isEmpty();
        // a rate limit that nothing says to wait for is not retried: the runtime fails the step at
        // once
        TestRunner.CaseResult limited =
                one(
                        HANDLED,
                        cases(
                                "  - name: limited\n    input: { message: hi }\n    models: { Replier: ok }\n    fail: { Replier: { always: true, category: rate_limit } }\n    expect:\n      result: fallback\n      ran: [ on_failure of delegate Replier ]\n      calls: { Replier: 1 }\n"));
        assertThat(limited.failures()).isEmpty();
    }

    @Test
    void ranFailsWhenNoHandlerTookOver() throws Exception {
        TestRunner.CaseResult r =
                one(
                        RETRY,
                        cases(
                                "  - name: no handler\n    input: { message: hi }\n    models: { Replier: ok }\n    expect:\n      ran: [ on_failure of delegate Replier ]\n"));
        assertThat(r.failures()).singleElement().asString().contains("ran: no handler took over");
    }

    @Test
    void onCallsFailsOnlyThoseCalls() throws Exception {
        TestRunner.CaseResult r =
                one(
                        RETRY,
                        cases(
                                "  - name: second only\n    input: { message: hi }\n    models: { Replier: [ a, b, c ] }\n    fail: { Replier: { on_calls: [1], category: server_error } }\n    expect:\n      calls: { Replier: 2 }\n      result: b\n"));
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void aSleepAdvancesVirtualTimeAtOnce() throws Exception {
        String sleepy =
                "workflow Main(message) {\n sleep 3h\n note \"awake\"\n return \"done\"\n}\n";
        long start = System.nanoTime();
        TestRunner.CaseResult r =
                one(
                        sleepy,
                        cases(
                                "  - name: sleeps\n    input: { message: hi }\n    clock: { start: '2026-10-01T07:00:00Z' }\n    expect:\n      elapsed_virtual: 3h\n      route: [ note ]\n      result: done\n"));
        assertThat(r.failures()).isEmpty();
        assertThat((System.nanoTime() - start) / 1_000_000_000).isLessThan(20);
    }

    @Test
    void elapsedVirtualCanBeAMatcherOnSecondsAndSaysWhatHappened() throws Exception {
        TestRunner.CaseResult r =
                one(
                        RETRY,
                        cases(
                                "  - name: wrong\n    input: { message: hi }\n    models: { Replier: ok }\n    fail: { Replier: { times: 1, category: timeout } }\n    expect:\n      elapsed_virtual: 5m\n"));
        assertThat(r.failures())
                .singleElement()
                .asString()
                .contains("expected 5m, the case waited 2s");
        TestRunner.CaseResult m =
                one(
                        RETRY,
                        cases(
                                "  - name: matcher\n    input: { message: hi }\n    models: { Replier: ok }\n    fail: { Replier: { times: 1, category: timeout } }\n    expect:\n      elapsed_virtual: { between: [1, 3] }\n"));
        assertThat(m.failures()).isEmpty();
    }

    @Test
    void aClassifierCanFailToo() throws Exception {
        String classified =
                """
                intentclassifier Intent {
                    model: "m"
                    choose kind: a | b
                }
                workflow Main(message) {
                    intentclassify Intent on message -> intent retry 1 backoff 1s on_failure {
                        return "person"
                    }
                    return intent.kind
                }
                """;
        TestRunner.CaseResult r =
                one(
                        classified,
                        cases(
                                "  - name: down\n    input: { message: hi }\n    models: { Intent: { kind: a } }\n    fail: { Intent: { always: true, category: rate_limit } }\n    expect:\n      result: person\n      ran: [ on_failure of intentclassify Intent ]\n      attempts: { Intent: 2 }\n"));
        assertThat(r.failures()).isEmpty();
    }

    // ---- tools, tasks, effects

    static final String MAILER =
            """
            tool Mail { use: webhook  url: env.HOOK_URL }
            tool Lookup { use: file  root: "docs"  mode: read }
            agent Sender { model: "m"  system: "Send mail."  tools: [Mail] }
            workflow Main(message) {
                delegate "Tell the team: {message}" to Sender -> sent
                return sent
            }
            """;

    @Test
    void aToolCallIsRecordedAsAnEffectAndNeverPerformed() throws Exception {
        TestRunner.CaseResult r =
                one(
                        MAILER,
                        cases(
                                "  - name: sends once\n    input: { message: hello }\n    models:\n      Sender: [ { call: Mail, with: { to: team, body: hello } }, sent ]\n    expect:\n      result: sent\n      calls: { Mail: 1, Sender: 2 }\n      effects:\n        - { tool: Mail, kind: webhook, target: env.HOOK_URL }\n"));
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void effectsNoneAndEffectsCountAssertWhatDidNotHappen() throws Exception {
        TestRunner.CaseResult quiet =
                one(
                        MAILER,
                        cases(
                                "  - name: no send\n    input: { message: hello }\n    models: { Sender: 'nothing to send' }\n    expect:\n      effects: none\n      calls: { Mail: 0 }\n"));
        assertThat(quiet.failures()).isEmpty();
        TestRunner.CaseResult wrong =
                one(
                        MAILER,
                        cases(
                                "  - name: sent but should not\n    input: { message: hello }\n    models:\n      Sender: [ { call: Mail, with: { body: x } }, done ]\n    expect:\n      effects: none\n"));
        assertThat(wrong.failures())
                .singleElement()
                .asString()
                .contains("effects: expected none, but 1 would have happened")
                .contains("Mail (webhook to env.HOOK_URL)");
        TestRunner.CaseResult count =
                one(
                        MAILER,
                        cases(
                                "  - name: count\n    input: { message: hello }\n    models:\n      Sender: [ { call: Mail, with: { body: x } }, { call: Mail, with: { body: y } }, done ]\n    expect:\n      effects: { count: 2 }\n"));
        assertThat(count.failures()).isEmpty();
    }

    @Test
    void aScriptedToolResultReachesTheAgentAndAToolCanFail() throws Exception {
        TestRunner.CaseResult r =
                one(
                        MAILER,
                        cases(
                                "  - name: result\n    input: { message: hello }\n    tools: { Mail: 'queued as 42' }\n    models:\n      Sender: [ { call: Mail, with: { body: x } }, 'it said queued as 42' ]\n    expect:\n      result: { contains: '42' }\n"));
        assertThat(r.failures()).isEmpty();
        TestRunner.CaseResult failing =
                one(
                        MAILER,
                        cases(
                                "  - name: fails\n    input: { message: hello }\n    models:\n      Sender: [ { call: Mail, with: { body: x } }, 'gave up' ]\n    fail: { Mail: { always: true, category: timeout } }\n    expect:\n      calls: { Mail: 1 }\n      effects: none\n"));
        assertThat(failing.failures()).isEmpty();
    }

    @Test
    void aToolThatDoesNotActAndWasNotScriptedFailsTheCase() throws Exception {
        String reader =
                "agent Reader { model: \"m\"  system: \"s\"  tools: [web_search] }\nworkflow Main(message) {\n delegate \"find {message}\" to Reader -> found\n return found\n}\n";
        TestRunner.CaseResult r =
                one(
                        reader,
                        cases(
                                "  - name: unscripted tool\n    input: { message: x }\n    models:\n      Reader: [ { call: web_search, with: { query: x } }, done ]\n"));
        assertThat(r.failures())
                .anyMatch(f -> f.contains("tool web_search has no scripted result (call 1)"));
    }

    static final String POLICY = """
            task_placeholder
            """;

    @Test
    void aTaskIsReplacedByItsScriptedResultAndCounted() throws Exception {
        String withTask =
                "workflow Main(message) {\n run RefundPolicy(amount = 40) -> verdict\n return verdict\n}\n";
        TestRunner.CaseResult r =
                one(
                        withTask,
                        cases(
                                "  - name: allowed\n    input: { message: x }\n    tasks: { RefundPolicy: { verdict: allow } }\n    expect:\n      calls: { RefundPolicy: 1 }\n      route: [ run RefundPolicy ]\n      result: { verdict: allow }\n"));
        assertThat(r.failures()).isEmpty();
        TestRunner.CaseResult unscripted =
                one(withTask, cases("  - name: unscripted task\n    input: { message: x }\n"));
        assertThat(unscripted.failures())
                .anyMatch(f -> f.contains("task RefundPolicy has no scripted result"));
    }

    @Test
    void aScriptedTaskResultWithTheReservedKeyValueSaysWhatToWriteInstead() throws Exception {
        String withTask =
                "workflow Main(message) {\n run RefundPolicy(amount = 40) -> verdict\n return verdict\n}\n";

        TestRunner.CaseResult r =
                one(
                        withTask,
                        cases(
                                "  - name: reserved\n    input: { message: x }\n    tasks: { RefundPolicy: { value: 12, verdict: allow } }\n"));

        assertThat(r.failures())
                .anyMatch(
                        f ->
                                f.contains("`value` is reserved in a scripted result")
                                        && f.contains("plain string")
                                        && f.contains("answer"));
    }

    // ---- people

    static final String ASKS =
            """
            workflow Main(message) {
                human_prompt "Publish {message}?" -> ok
                alt (ok == "yes") {
                    note "published"
                    return "published"
                } else {
                    return "held"
                }
            }
            """;

    @Test
    void humansAnswerInOrderAndEachQuestionIsRecorded() throws Exception {
        TestRunner.CaseResult r =
                one(
                        ASKS,
                        cases(
                                "  - name: yes\n    input: { message: post }\n    humans: [ 'yes' ]\n    expect:\n      result: published\n      asked:\n        - { prompt: { contains: 'Publish post' }, kind: prompt }\n"));
        assertThat(r.failures()).isEmpty();
        TestRunner.CaseResult no =
                one(
                        ASKS,
                        cases(
                                "  - name: no\n    input: { message: post }\n    humans: [ 'no' ]\n    expect:\n      result: held\n      asked: [ 'Publish post' ]\n"));
        assertThat(no.failures()).isEmpty();
    }

    @Test
    void anUnansweredQuestionSuspendsTheRunAndTheCaseCanSayWhatWasAsked() throws Exception {
        TestRunner.CaseResult r =
                one(
                        ASKS,
                        cases(
                                "  - name: waits\n    input: { message: post }\n    expect:\n      final: suspended\n      asked: [ 'Publish post' ]\n      route: []\n"));
        assertThat(r.failures()).isEmpty();
        TestRunner.CaseResult missing =
                one(ASKS, cases("  - name: forgot\n    input: { message: post }\n"));
        assertThat(missing.failures())
                .singleElement()
                .asString()
                .contains("expected the run to end completed, it ended suspended");
    }

    @Test
    void resumeContinuesTheSameRunFromItsJournalWithTheNextAnswer() throws Exception {
        TestRunner.CaseResult r =
                one(
                        ASKS,
                        cases(
                                "  - name: resumed\n    input: { message: post }\n    resume: [ 'yes' ]\n    expect:\n      final: completed\n      result: published\n      asked: [ 'Publish post' ]\n"));
        // asked once: on the replay the journal already holds the answer, so nobody is asked again
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void askedReportsTheCountAndTheQuestionsWhenItDiffers() throws Exception {
        TestRunner.CaseResult r =
                one(
                        ASKS,
                        cases(
                                "  - name: wrong\n    input: { message: post }\n    humans: [ 'yes' ]\n    expect:\n      asked: none\n"));
        assertThat(r.failures())
                .singleElement()
                .asString()
                .contains("expected nobody to be asked")
                .contains("Publish post?");
        TestRunner.CaseResult text =
                one(
                        ASKS,
                        cases(
                                "  - name: wrong text\n    input: { message: post }\n    humans: [ 'yes' ]\n    expect:\n      asked: [ 'something else' ]\n"));
        assertThat(text.failures()).singleElement().asString().contains("asked[1]");
    }

    // ---- usage

    @Test
    void tokenUsageIsDeterministicAndACaseCanSetIt() throws Exception {
        String yaml =
                cases(
                        "  - name: usage\n    input: { message: hi }\n    models: { Replier: ok }\n    usage: { Replier: { in: 120, out: 40 } }\n    expect:\n      tokens: { max: 160 }\n",
                        "  - name: too many\n    input: { message: hi }\n    models: { Replier: ok }\n    usage: { Replier: { in: 120, out: 40 } }\n    expect:\n      tokens: { max: 100 }\n");
        List<TestRunner.CaseResult> r = Wtest.run(dir, RETRY, yaml);
        assertThat(r.get(0).failures()).isEmpty();
        assertThat(r.get(1).failures())
                .singleElement()
                .asString()
                .contains("tokens: expected lte 100, got 160");
        List<TestRunner.CaseResult> again = Wtest.run(dir, RETRY, yaml);
        assertThat(again.get(1).failures()).isEqualTo(r.get(1).failures());
    }
}
