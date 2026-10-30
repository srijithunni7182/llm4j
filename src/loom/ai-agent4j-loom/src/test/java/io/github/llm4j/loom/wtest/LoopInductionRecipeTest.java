package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 19 R18, loop induction: the documented recipe in the evaluation design skill. An agent
 * that never converges is scripted to give the same unhelpful answer; the bound must fire on its
 * own, the work so far must be said, and the run must end instead of spinning. The recipe is
 * tested so the guide cannot promise what {@code weave test} does not do.
 */
class LoopInductionRecipeTest {

    @TempDir Path dir;

    private static final String SCRIPT =
            """
            agent Worker { model: "m"  system: "Try the task." }
            workflow Main(task) {
                loop until (answer == "done") max 3 {
                    delegate "Try {task}, round {_loopRound}" to Worker -> answer
                } on_exhausted {
                    note "gave up after {_loopRounds} rounds"
                }
                return "stopped"
            }
            """;

    @Test
    void theBoundFiresAfterThreeRoundsAndTheRunEndsWithTheNoteInsteadOfSpinning() throws Exception {
        var results =
                Wtest.run(
                        dir,
                        SCRIPT,
                        "script: ../main.loom\nworkflow: Main\ncases:\n"
                                + "  - name: never converges\n"
                                + "    input: { task: fix it }\n"
                                + "    repeat: true\n"
                                + "    models: { Worker: unhelpful }\n"
                                + "    expect:\n"
                                + "      final: completed\n"
                                + "      result: stopped\n"
                                + "      calls: { Worker: 3 }\n"
                                + "      route: [ delegate Worker, note ]\n");

        assertThat(results).allSatisfy(r -> assertThat(r.failures()).as(r.name()).isEmpty());
    }

    @Test
    void ifTheBoundWereMissingTheCaseWouldSayWorkerWasCalledMoreOftenThanThree() throws Exception {
        String unbounded = SCRIPT.replace(" max 3", " max 5");

        var results =
                Wtest.run(
                        dir,
                        unbounded,
                        "script: ../main.loom\nworkflow: Main\ncases:\n"
                                + "  - name: never converges\n"
                                + "    input: { task: fix it }\n"
                                + "    repeat: true\n"
                                + "    models: { Worker: unhelpful }\n"
                                + "    expect:\n"
                                + "      calls: { Worker: 3 }\n");

        assertThat(results.get(0).passed()).as("a looser bound is noticed").isFalse();
    }
}
