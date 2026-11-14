package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Phase 34a, F5 and D15 (principle 22): {@code children:} in a {@code weave test} case scripts a
 * child as a list of states, in the style of {@code remotes:}. The case runs the supervisor with no
 * model and no child script, on the virtual clock, so a workflow that starts and watches children
 * is tested without a real clock or racing threads.
 */
class StubbedChildTest {

    @TempDir Path dir;

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();

    static final String SCRIPT =
            """
            agent Researcher { model: "gemini-2.5-flash" system: "Research." }
            workflow Research(topic) {
                delegate "Find {topic}" to Researcher -> notes
                return notes
            }
            workflow Quick(q) {
                start Research(topic = q) budget { tokens: 100000 } -> work
                wait work timeout 20m -> findings
                set seen = work.status
                return findings
            }
            workflow Reader(topic) progress { stage: string, done: number } {
                report { stage: "start", done: 0 }
                delegate "Find {topic}" to Researcher -> notes
                return notes
            }
            workflow Stalled(q) {
                start Reader(topic = q) budget { tokens: 100000 } -> work
                supervise work { assert progress.stage changes within 3m { cancel use partial } }
                wait work timeout 20m -> findings
                set stage = work.progress.stage
                set seen = work.status
                return seen
            }
            workflow Idle(q) {
                start Research(topic = q) budget { tokens: 100000 } -> work
                supervise work { when idle > 3m { cancel use partial } }
                wait work timeout 20m -> findings
                set seen = work.status
                return seen
            }
            workflow Watched(q) {
                start Research(topic = q) budget { tokens: 100000 } -> work
                supervise work { when elapsed > 5m { cancel use partial } }
                wait work timeout 20m -> findings
                set seen = work.status
                return seen
            }
            """;

    int run(String tests, String... args) throws Exception {
        Files.writeString(dir.resolve("main.loom"), SCRIPT);
        Path t = Files.createDirectories(dir.resolve("tests"));
        Files.writeString(t.resolve("main.test.yaml"), tests);
        TestCommand c = new TestCommand();
        List<String> all = new ArrayList<>(List.of(dir.resolve("main.loom").toString()));
        all.addAll(List.of(args));
        new CommandLine(c).parseArgs(all.toArray(new String[0]));
        out.reset();
        err.reset();
        WeaveEnv env =
                new WeaveEnv(
                        m -> null,
                        q -> "",
                        new PrintStream(out, true),
                        new PrintStream(err, true),
                        Clock.systemUTC(),
                        d -> {},
                        cmd -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                        List.of("weave"),
                        k -> null);
        return TestCommand.run(c, env);
    }

    private static String cases(String workflow, String body) {
        return "script: ../main.loom\nworkflow: " + workflow + "\ncases:\n" + body;
    }

    @Test
    void aStubbedChildCompletesWithTheScriptedResultAndNoModelIsCalled() throws Exception {
        int code =
                run(
                        cases(
                                "Quick",
                                """
                                  - name: a scripted child completes
                                    input: { q: "peru" }
                                    children:
                                      Research:
                                        - { state: working, after: 1m }
                                        - { state: completed, result: "Lima.", after: 1m }
                                    expect:
                                      result: { contains: "Lima" }
                                      children: { Research: { status: completed } }
                                      starts: [ Research ]
                                """));

        assertThat(code).as(out + "\n" + err).isZero();
    }

    @Test
    void aStubbedChildCanFailWithAReason() throws Exception {
        int code =
                run(
                        cases(
                                "Quick",
                                """
                                  - name: a scripted child fails
                                    input: { q: "peru" }
                                    children:
                                      Research:
                                        - { state: failed, reason: "no sources" }
                                    expect:
                                      children: { Research: { status: failed } }
                                """));

        assertThat(code).as(out + "\n" + err).isZero();
    }

    @Test
    void theStubRunsOnTheVirtualClockSoARuleOfTheSupervisorFiresOnTime() throws Exception {
        int code =
                run(
                        cases(
                                "Watched",
                                """
                                  - name: a child that works too long is cancelled by the elapsed rule
                                    input: { q: "peru" }
                                    children:
                                      Research:
                                        - { state: working, after: 6m }
                                        - { state: completed, result: "too late", after: 1m }
                                    expect:
                                      result: { contains: "cancelled" }
                                      children: { Research: { status: cancelled } }
                                  - name: the same child finishing in time is left alone
                                    input: { q: "peru" }
                                    children:
                                      Research:
                                        - { state: working, after: 2m }
                                        - { state: completed, result: "in time", after: 1m }
                                    expect:
                                      result: { contains: "completed" }
                                      children: { Research: { status: completed } }
                                """));

        assertThat(code).as(out + "\n" + err).isZero();
    }

    @Test
    void aScriptedChildThatSitsStillForLongerThanTheIdleRuleIsCancelledAndOneThatKeepsWorkingIsNot() throws Exception {
        int code =
                run(
                        cases(
                                "Idle",
                                """
                                  - name: four quiet minutes is idle
                                    input: { q: "peru" }
                                    children:
                                      Research:
                                        - { state: working, after: 4m }
                                        - { state: completed, result: "late", after: 1m }
                                    expect:
                                      children: { Research: { status: cancelled } }
                                  - name: a child that moves every two minutes is never idle
                                    input: { q: "peru" }
                                    children:
                                      Research:
                                        - { state: working, after: 2m }
                                        - { state: working, after: 2m }
                                        - { state: completed, result: "on time", after: 1m }
                                    expect:
                                      children: { Research: { status: completed } }
                                """));

        assertThat(code).as(out + "\n" + err).isZero();
    }

    @Test
    void aScriptedChildCanReportProgressAndTheParentReadsTheLatest() throws Exception {
        int code =
                run(
                        cases(
                                "Stalled",
                                """
                                  - name: the parent reads what the scripted child last reported
                                    input: { q: "peru" }
                                    children:
                                      Reader:
                                        - { state: working, progress: { stage: reading, done: 1 }, after: 1m }
                                        - { state: completed, progress: { stage: writing, done: 2 }, result: "x", after: 1m }
                                    expect:
                                      result: { contains: "completed" }
                                      children: { Reader: { status: completed } }
                                """));

        assertThat(code).as(out + "\n" + err).isZero();
    }

    @Test
    void aProgressAssertionFiresWhenTheFieldStandsStillAndNotWhenItMoves() throws Exception {
        int code =
                run(
                        cases(
                                "Stalled",
                                """
                                  - name: a field that stops changing is caught
                                    input: { q: "peru" }
                                    children:
                                      Reader:
                                        - { state: working, progress: { stage: reading }, after: 1m }
                                        - { state: working, after: 1m }
                                        - { state: working, after: 1m }
                                        - { state: working, after: 1m }
                                        - { state: working, after: 1m }
                                        - { state: working, after: 1m }
                                        - { state: completed, result: "late", after: 1m }
                                    expect:
                                      result: { contains: "cancelled" }
                                      children: { Reader: { status: cancelled } }
                                  - name: a field that keeps moving is left alone
                                    input: { q: "peru" }
                                    children:
                                      Reader:
                                        - { state: working, progress: { stage: reading }, after: 2m }
                                        - { state: working, progress: { stage: writing }, after: 2m }
                                        - { state: working, progress: { stage: checking }, after: 2m }
                                        - { state: completed, result: "in time", after: 1m }
                                    expect:
                                      result: { contains: "completed" }
                                      children: { Reader: { status: completed } }
                                """));

        assertThat(code).as(out + "\n" + err).isZero();
    }

    @Test
    void aProgressStepNeedsTheWorkflowToDeclareThatField() throws Exception {
        assertThat(check("      Research:\n        - { state: completed, progress: { stage: x }, result: \"x\" }\n"))
                .contains("progress")
                .contains("declares no progress");
        assertThat(check("      Reader:\n        - { state: completed, progress: { nothing: x }, result: \"x\" }\n"))
                .contains("nothing")
                .contains("stage");
    }

    // ---- the checker says what is wrong with children: ----

    private String check(String children) throws Exception {
        int code =
                run(
                        cases(
                                "Quick",
                                "  - name: c\n    input: { q: \"x\" }\n    children:\n" + children
                                        + "    expect:\n      result: { contains: \"x\" }\n"),
                        "--check");
        assertThat(code).as("a bad children: is refused before anything runs").isNotZero();
        return out + "\n" + err;
    }

    @Test
    void aChildrenEntryMustNameAWorkflowOfTheScript() throws Exception {
        assertThat(check("      Nobody:\n        - { state: completed, result: \"x\" }\n"))
                .contains("children: 'Nobody' is not a workflow of the script");
    }

    @Test
    void aStateMustBeOneTheStubKnows() throws Exception {
        assertThat(check("      Research:\n        - { state: dancing }\n")).contains("state 'dancing'");
    }

    @Test
    void theLastStepMustSettleTheChildOrPauseIt() throws Exception {
        assertThat(check("      Research:\n        - { state: working, after: 1m }\n")).contains("last step");
    }

    @Test
    void anAfterMustBeADuration() throws Exception {
        assertThat(check("      Research:\n        - { state: completed, after: soon }\n")).contains("after");
    }
}
