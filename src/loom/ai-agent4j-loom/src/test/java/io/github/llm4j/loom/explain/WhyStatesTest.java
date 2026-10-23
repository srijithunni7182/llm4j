package io.github.llm4j.loom.explain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.travel.TravelFixtures;
import io.github.llm4j.loom.wtest.LoomTest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R9, R10, R12, R13 of spec 13: suspended and failed runs, attempts, and speed. */
class WhyStatesTest {

    @TempDir Path dir;

    private Why.Report explain(TravelFixtures.Case c) throws Exception {
        var real = TravelFixtures.run(dir, c);
        return Why.explain(
                real.script(),
                c.workflow(),
                c.input(),
                real.run().journal(),
                Why.Request.path(),
                Set.of());
    }

    @Test
    void aSuspendedRunShowsTheQuestionItIsWaitingOnAndEveryChoiceBeforeIt() throws Exception {
        var base =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals("human answer"))
                        .findFirst()
                        .orElseThrow();
        Why.Report r =
                explain(
                        new TravelFixtures.Case(
                                "waiting",
                                base.script(),
                                "Main",
                                base.input(),
                                t -> t.model("A", "a draft")));
        assertThat(r.status()).isEqualTo("suspended");
        assertThat(r.notes())
                .anyMatch(
                        n ->
                                n.startsWith("The run is waiting at")
                                        && n.contains("Approve a draft?"));
        assertThat(r.entries())
                .extracting(Why.Entry::kind)
                .containsExactly("delegate", "human_prompt");
    }

    @Test
    void aFailedRunShowsTheFailingStepAndTheError() throws Exception {
        var failing =
                new TravelFixtures.Case(
                        "failing",
                        """
                agent A { model: "m" system: "x" }
                workflow Main(topic) {
                    delegate "Try {topic}" to A -> out
                    return out
                }
                """,
                        "Main",
                        Map.of("topic", "t"),
                        t ->
                                t.model("A", "never")
                                        .failing("A", LoomTest.Failure.always("server_error")));
        Why.Report r = explain(failing);
        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.reason()).contains("scripted server error");
        assertThat(r.notes()).anyMatch(n -> n.startsWith("The run failed at Main/s0"));
        assertThat(WhyRenderer.text(r))
                .contains("delegate A  → failed: [scripted] scripted server error");
    }

    @Test
    void aRewindIsSeenAsOneAttemptAfterAnotherWithTheValuesThatCausedIt() throws Exception {
        var rewind =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals("rewind"))
                        .findFirst()
                        .orElseThrow();
        Why.Report r = explain(rewind);
        assertThat(r.entries()).extracting(Why.Entry::generation).containsExactly(1, 1, 2, 2);
        assertThat(r.entries().get(1).kind()).isEqualTo("rewind");
        assertThat(r.entries().get(2).summary()).contains("replied (4 chars)");
    }

    @Test
    void aRunOfFiveThousandStepsIsExplainedInUnderTwoSeconds() throws Exception {
        int items = 2500;
        List<String> list = new java.util.ArrayList<>();
        for (int i = 0; i < items; i++) list.add("i" + i);
        var big =
                new TravelFixtures.Case(
                        "big",
                        """
                agent Planner { model: "m" output_schema: { items: list<string> } }
                agent Worker { model: "m" system: "x" }
                workflow Main(topic) {
                    delegate "Plan {topic}" to Planner -> plan
                    for each item in plan.items {
                        delegate "Do {item}" to Worker -> out
                    }
                    return out
                }
                """,
                        "Main",
                        Map.of("topic", "t"),
                        t ->
                                t.typed("Planner", Map.of("items", list))
                                        .model("Worker", "done")
                                        .repeatLast());
        var real = TravelFixtures.run(dir, big);
        assertThat(real.run().status()).isEqualTo("completed");
        assertThat(real.run().stepIds().size()).isGreaterThanOrEqualTo(2500);
        long t0 = System.nanoTime();
        Why.Report r =
                Why.explain(
                        real.script(),
                        "Main",
                        big.input(),
                        real.run().journal(),
                        Why.Request.path(),
                        Set.of());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println(
                "# why over " + real.run().stepIds().size() + " steps took " + ms + " ms");
        assertThat(ms).isLessThan(2000);
        assertThat(r.entries().size()).isGreaterThan(2500);
    }
}
