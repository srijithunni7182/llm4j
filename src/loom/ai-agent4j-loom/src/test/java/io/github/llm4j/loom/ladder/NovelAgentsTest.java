package io.github.llm4j.loom.ladder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.ScriptValidator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Agents nobody described when the language was designed. Each is written with the same words (a
 * ladder, measures, the standard library) and run by the same engine; if one could not be said,
 * that would be a missing mechanism and not a reason to write Java for it.
 */
@Tag("L6")
class NovelAgentsTest {

    @TempDir Path dir;

    private static List<String> problems(LadderHarness h) {
        return new ScriptValidator()
                        .validate(h.script, new ScriptValidator.Context().registeredTools(Set.of()))
                        .stream()
                        .map(ScriptValidator.Problem::toString)
                        .toList();
    }

    @Test
    void aReplyDrafterIsJudgedByRewritesComplaintsAndHowFastCustomersAreHelped()
            throws IOException {
        LadderHarness h = LadderHarness.fromResource("novel/reply_drafter.loom", dir);
        assertThat(problems(h)).isEmpty();
        LadderEngine e = h.engine;
        Map<String, String> f =
                Map.of("queue", "billing", "mood", "calm", "customer_tier", "standard");
        e.standing("billing", "id-a", "m-1");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++)
            ids.add(
                    h.artifact(
                            "billing",
                            "Dear customer, your refund is on its way.",
                            "accept",
                            "Dear customer, your refund is on its way.",
                            "draft",
                            "id-a",
                            1,
                            f,
                            30_000));
        assertThat(e.afterResponse("billing"))
                .isEmpty(); // nobody knows yet how fast they were helped
        for (int i = 0; i < 8; i++) e.outcome(ids.get(i), "resolved_in_hours", 6, null);
        assertThat(e.openProposal("billing")).isPresent();
        e.approve("billing", "support-lead", "good quarter");
        assertThat(e.standing("billing", "id-a", "m-1").rungName()).isEqualTo("send");

        // two complaints within twenty cases take it back down
        String a = h.artifact("billing", "Hello", "accept", "Hello", "send", "id-a", 1, f, 0);
        String b =
                h.artifact(
                        "billing", "Hello again", "accept", "Hello again", "send", "id-a", 1, f, 0);
        assertThat(e.outcome(a, "complaint", null, "rude")).isEmpty();
        assertThat(e.outcome(b, "complaint", null, "rude"))
                .extracting(LadderEngine.Change::kind)
                .containsExactly("demoted");

        // an angry customer or an enterprise one always reaches a person
        e.set("billing", "send", true, "drill", "ops");
        LevelLog.Standing s = e.standing("billing", "id-a", "m-1");
        for (Map<String, String> fields :
                List.of(Map.of("mood", "angry"), Map.of("customer_tier", "enterprise"))) {
            LCase c =
                    new LCase(
                            "x",
                            "Reply",
                            "billing",
                            LadderHarness.START,
                            "send",
                            "",
                            "id-a",
                            1,
                            1,
                            false,
                            fields,
                            "m-1",
                            null,
                            null,
                            null,
                            null,
                            false,
                            null,
                            List.of(),
                            false,
                            null,
                            null,
                            null,
                            null,
                            false,
                            null,
                            null,
                            List.of());
            assertThat(
                            e.plan(
                                            "billing",
                                            "x",
                                            s,
                                            HistoryBuilder.record(c),
                                            e.history("billing", 1),
                                            h.clock.millis())
                                    .how())
                    .isEqualTo("escalate");
        }
    }

    @Test
    void aSchedulerIsJudgedByTheOtherSidesLateAnswer() throws IOException {
        LadderHarness h = LadderHarness.fromResource("novel/calendar_slot.loom", dir);
        assertThat(problems(h)).isEmpty();
        LadderEngine e = h.engine;
        Map<String, String> f = Map.of("attendees", "4", "urgency", "low");
        e.standing("all", "id-a", "m-1");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++)
            ids.add(
                    h.artifact(
                            "all",
                            "Tue 10:00",
                            "accept",
                            "Tue 10:00",
                            "propose",
                            "id-a",
                            1,
                            f,
                            5_000));
        for (int i = 0; i < 9; i++) e.outcome(ids.get(i), "confirmed", null, null);
        assertThat(e.openProposal("all")).isEmpty(); // nine answers: not enough
        e.outcome(ids.get(9), "confirmed", null, null);
        assertThat(e.openProposal("all")).isPresent();
        e.approve("all", "office-manager", "ok");

        List<String> booked = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            booked.add(
                    h.artifact("all", "Wed 14:00", "accept", "Wed 14:00", "book", "id-a", 1, f, 0));
        for (int i = 0; i < 3; i++) e.outcome(booked.get(i), "declined", null, null);
        assertThat(e.log().current("all").orElseThrow().rungName()).isEqualTo("propose");
    }

    @Test
    void aReviewerPickingOneOfFortyCategoriesNeedsNoSpecialCase() throws IOException {
        String categories =
                IntStream.rangeClosed(1, 40)
                        .mapToObj(i -> "cat" + i)
                        .collect(Collectors.joining(", "));
        LadderHarness h =
                new LadderHarness(
                        """
                agent Sorter { model: "m" system: "s" output_schema: { choice: string, reasoning: string } }
                measure agrees(c) = c.response.choice == c.proposal.choice
                measure category(c) = c.proposal.choice
                ladder SortLadder {
                    rung watch { mode: blind }
                    rung act {
                        mode: autonomous
                        climb when history.count >= 40 and history.rate(agrees).lower95 >= 0.85 and history.by(category).count().min() >= 1
                        slip when history.last(20).rate(agrees) < 0.7
                        sample 10% blind
                    }
                    climbing is automatic
                }
                decision Sort { proposed by: Sorter choices: @CATEGORIES@, escalate ask: sorters ladder: SortLadder ceiling: act when the agent changes: start over }
                workflow W() { decide Sort -> v note "{v}" }
                """
                                .replace("@CATEGORIES@", categories));
        assertThat(problems(h)).isEmpty();
        h.engine.standing("all", "id-a", "m-1");
        for (int i = 1; i <= 39; i++) h.answered("all", "cat" + i, "cat" + i);
        assertThat(h.engine.afterResponse("all")).isEmpty(); // 39 of 40 needed
        h.answered("all", "cat40", "cat40");
        assertThat(h.engine.afterResponse("all"))
                .extracting(LadderEngine.Change::kind)
                .containsExactly("promoted");
        for (int i = 0; i < 10; i++)
            h.answered(
                    "all", "cat1", "cat7", "act", "id-a", 1,
                    Map.of()); // it starts to get them wrong
        assertThat(h.engine.afterResponse("all"))
                .extracting(LadderEngine.Change::kind)
                .containsExactly("demoted");
    }
}
