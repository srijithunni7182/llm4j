package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** docs/specs/32-a2a-integration R33: a remote agent and a channel are scripted in a test file, so a workflow that calls one is tested offline and its steps count in coverage. */
class ScriptedRemoteTest {

    @TempDir Path dir;

    static final String BUY = """
            agent_channel Desk { audience: staff max_questions: 2 }
            remote Supplier {
                card: "https://supplier.example.com/.well-known/agent-card.json"
                agent_channel: Desk
                timeout: 2h
            }
            workflow Main(part) {
                delegate "Quote {part}" to Supplier -> quote
                    on_failure { return "no quote" }
                return quote
            }
            """;

    static String cases(String... body) {
        return "script: ../main.loom\nworkflow: Main\ncases:\n" + String.join("\n", body) + "\n";
    }

    TestRunner.CaseResult one(String script, String yaml) throws Exception {
        List<TestRunner.CaseResult> r = Wtest.run(dir, script, yaml);
        assertThat(r).hasSize(1);
        return r.get(0);
    }

    @Test
    void aRemoteThatCompletesAtOnceAnswersTheCall() throws Exception {
        var r = one(BUY, cases("""
                  - name: completes
                    input: { part: bolt }
                    remotes:
                      Supplier:
                        - { state: completed, text: "42 each" }
                    expect:
                      result: "42 each"
                """));
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void aPlainMessageReplyEndsTheStepWithNoTask() throws Exception {
        var r = one(BUY, cases("""
                  - name: plain
                    input: { part: bolt }
                    remotes:
                      Supplier:
                        - { reply: "42 each" }
                    expect:
                      result: "42 each"
                """));
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void aQuestionIsAnsweredByTheScriptedChannelAndTheRunCarriesOnThroughItsStates() throws Exception {
        var r = one(BUY, cases("""
                  - name: asks
                    input: { part: bolt }
                    remotes:
                      Supplier:
                        - { state: working }
                        - { state: input_required, question: "Which date?" }
                        - { state: working }
                        - { state: completed, text: "42 each, 30 days" }
                    channels:
                      Desk:
                        - "Within 30 days."
                    expect:
                      result: "42 each, 30 days"
                      asked: [ "Which date?" ]
                """));
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void aFailedOrRejectedTaskTakesTheOnFailureHandler() throws Exception {
        var r = one(BUY, cases("""
                  - name: rejected
                    input: { part: bolt }
                    remotes:
                      Supplier:
                        - { state: rejected, message: "not our product" }
                    expect:
                      result: "no quote"
                      ran: [ "on_failure of remote Supplier" ]
                """));
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void aRemoteThatNeverFinishesFailsTheStepAtTheDeadlineInVirtualTime() throws Exception {
        long start = System.nanoTime();
        var r = one(BUY, cases("""
                  - name: never finishes
                    input: { part: bolt }
                    remotes:
                      Supplier:
                        - { state: working }
                    expect:
                      result: "no quote"
                """));
        assertThat(r.failures()).isEmpty();
        assertThat((System.nanoTime() - start) / 1_000_000_000).as("two hours passed in virtual time, not real time").isLessThan(20);
    }

    @Test
    void aStepNobodyScriptedFailsTheCaseInsteadOfGuessing() throws Exception {
        var r = one(BUY, cases("""
                  - name: unscripted
                    input: { part: bolt }
                    expect:
                      result: "42 each"
                """));
        assertThat(r.failures()).isNotEmpty();
        assertThat(String.join("\n", r.failures())).contains("Supplier").contains("scripted");
    }

    @Test
    void aStepsCountsInCoverageAndCanBeAssertedOnByItsLabel() throws Exception {
        var r = one(BUY, cases("""
                  - name: ran
                    input: { part: bolt }
                    remotes:
                      Supplier:
                        - { state: completed, text: "42 each" }
                    expect:
                      route: [ "remote Supplier" ]
                """));
        assertThat(r.failures()).isEmpty();
        assertThat(r.steps()).as("the remote step is one of the steps reached").contains("Main/s0");
    }

    @Test
    void theTestFileIsCheckedAgainstTheScript() throws Exception {
        var suite = Wtest.load(dir, BUY, cases("""
                  - name: wrong names
                    input: { part: bolt }
                    remotes:
                      Nobody:
                        - { state: completed, text: "x" }
                      Supplier:
                        - { state: sulking }
                    channels:
                      Missing:
                        - "x"
                """));
        String problems = String.join("\n", suite.problems());
        assertThat(problems).contains("Nobody").contains("sulking").contains("Missing");
    }
}
