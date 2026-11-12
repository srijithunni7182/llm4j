package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.loom.execution.ControlledChildScheduler;
import io.github.llm4j.loom.execution.HarnessExecutor;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guard G21 of phase 34b (D6): declared progress is validated against the declaration, bounded
 * (dropped and recorded beyond the bounds), journaled in the child, readable by the parent after a
 * restart, and as of the checkpoint after a rewind. Written before the runtime.
 */
class ChildProgressTest {

    private final ChildHarness h = new ChildHarness();

    private static final String RESEARCH =
            """
            workflow Research(topic) progress { stage: string, done: number, live: boolean } {
                report { stage: "searching", done: 0, live: true }
                run Fine() -> got
                report { stage: "reading", done: 1, live: false }
                return got
            }
            """;

    private HarnessExecutor run(String source) {
        HarnessExecutor e = h.parent(source, new ControlledChildScheduler());
        e.executeWorkflow("Main", Map.of("q", "x"));
        return e;
    }

    private long progressEntries() {
        return h.onlyChild().all().values().stream().filter(en -> "progress".equals(en.kind())).count();
    }

    @Test
    void theParentReadsTheLatestReportOfTheChildFieldByField() {
        HarnessExecutor e =
                run(
                        RESEARCH
                                + """
                                workflow Main(q) {
                                    start Research(topic = q) budget { tokens: 10 } -> work
                                    wait work timeout 1m -> out
                                    set stage = work.progress.stage
                                    set done = work.progress.done
                                    set live = work.progress.live
                                }
                                """);

        assertThat(e.getContext().getVariable("stage")).isEqualTo("reading");
        assertThat(String.valueOf(e.getContext().getVariable("done"))).isEqualTo("1");
        assertThat(String.valueOf(e.getContext().getVariable("live"))).isEqualTo("false");
        assertThat(progressEntries()).as("both reports are kept as history").isEqualTo(2);
    }

    @Test
    void aChildThatHasNotReportedYetHasEmptyProgressNotAnError() {
        HarnessExecutor e =
                run(
                        RESEARCH
                                + """
                                workflow Main(q) {
                                    start Research(topic = q) budget { tokens: 10 } -> work
                                    set before = "{work.progress.stage}"
                                    wait work timeout 1m -> out
                                }
                                """);

        assertThat(e.getContext().getVariable("before")).isEqualTo("");
    }

    @Test
    void aValueOfTheWrongTypeFailsTheStepAndNamesTheField() {
        run(
                """
                workflow Research(topic) progress { done: number } {
                    set bad = "many"
                    report { done: bad }
                }
                workflow Main(q) {
                    start Research(topic = q) budget { tokens: 10 } -> work
                    wait work timeout 1m -> out
                    set seen = work.status
                    set why = work.failure
                }
                """);
        // the child failed: read what the parent saw
        assertThat(h.parent.getContext().getVariable("seen")).isEqualTo("failed");
        assertThat(String.valueOf(h.parent.getContext().getVariable("why")))
                .contains("done")
                .contains("number");
    }

    @Test
    void aValueOutsideAnEnumFailsTheStep() {
        run(
                """
                workflow Research(topic) progress { phase: enum["a", "b"] } {
                    set bad = "c"
                    report { phase: bad }
                }
                workflow Main(q) {
                    start Research(topic = q) budget { tokens: 10 } -> work
                    wait work timeout 1m -> out
                    set why = work.failure
                }
                """);

        assertThat(String.valueOf(h.parent.getContext().getVariable("why"))).contains("phase").contains("\"a\"");
    }

    @Test
    void aReportOverTheSizeBoundIsDroppedAndRecordedAndTheChildGoesOn() {
        h.task("Big", ctx -> TaskResult.value("x".repeat(2500)));
        HarnessExecutor e =
                run(
                        """
                        workflow Research(topic) progress { stage: string, note: string } {
                            report { stage: "small", note: "ok" }
                            run Big() -> big
                            report { stage: "huge", note: big.value }
                            return "done"
                        }
                        workflow Main(q) {
                            start Research(topic = q) budget { tokens: 10 } -> work
                            wait work timeout 1m -> out
                            set seen = work.status
                            set stage = work.progress.stage
                        }
                        """);

        assertThat(e.getContext().getVariable("seen")).as("not an error that stops the child").isEqualTo("completed");
        assertThat(e.getContext().getVariable("stage")).as("the last report that fitted").isEqualTo("small");
        assertThat(h.onlyChild().all().values())
                .as("the drop is recorded, not silent")
                .anyMatch(en -> "progress-dropped".equals(en.kind()) && String.valueOf(en.value()).contains("2000"));
    }

    @Test
    void thousandReportsAreKeptAndTheNextIsDroppedAndRecorded() {
        HarnessExecutor e =
                run(
                        """
                        workflow Research(topic) progress { stage: string } {
                            loop until (never == "yes") max 1001 {
                                report { stage: "again" }
                            }
                            return "done"
                        }
                        workflow Main(q) {
                            start Research(topic = q) budget { tokens: 10 } -> work
                            wait work timeout 1m -> out
                            set seen = work.status
                        }
                        """);

        assertThat(e.getContext().getVariable("seen")).isEqualTo("completed");
        assertThat(progressEntries()).isEqualTo(1000);
        assertThat(h.onlyChild().all().values())
                .anyMatch(en -> "progress-dropped".equals(en.kind()) && String.valueOf(en.value()).contains("1000"));
    }

    @Test
    void aRestartedParentReadsTheSameProgressAndNothingIsReportedTwice() {
        String source =
                RESEARCH
                        + """
                        workflow Main(q) {
                            start Research(topic = q) budget { tokens: 10 } -> work
                            wait work timeout 1m -> out
                            set stage = work.progress.stage
                        }
                        """;
        run(source);
        long before = progressEntries();

        HarnessExecutor again = h.parent(source, new ControlledChildScheduler());
        again.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(again.getContext().getVariable("stage")).isEqualTo("reading");
        assertThat(progressEntries()).isEqualTo(before);
    }

    @Test
    void afterARewindTheParentReadsTheValueAsOfTheCheckpoint() {
        ControlledChildScheduler scheduler = new ControlledChildScheduler();
        h.task(
                "LetItNap",
                ctx -> {
                    scheduler.runNext();
                    return TaskResult.value("napping");
                });
        HarnessExecutor e =
                h.parent(
                        """
                        workflow Research(topic) progress { stage: string } {
                            report { stage: "early" }
                            checkpoint collected
                            report { stage: "late" }
                            sleep 1h
                            return "done"
                        }
                        workflow Main(q) {
                            start Research(topic = q) budget { tokens: 10 } -> work
                            run LetItNap() -> nap
                            set atFirst = work.progress.stage
                            cancel work rewind to collected at most 1 times
                            set afterRewind = work.progress.stage
                        }
                        """,
                        scheduler);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(e.getContext().getVariable("atFirst")).isEqualTo("late");
        assertThat(e.getContext().getVariable("afterRewind")).as("the report after the checkpoint is a discarded generation").isEqualTo("early");
    }
}
