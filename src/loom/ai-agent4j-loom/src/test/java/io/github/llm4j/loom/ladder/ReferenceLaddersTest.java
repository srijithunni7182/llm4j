package io.github.llm4j.loom.ladder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.RungDef;
import io.github.llm4j.loom.autonomy.Rec;
import io.github.llm4j.loom.autonomy.expr.V;
import io.github.llm4j.loom.execution.ScriptValidator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Three agents with three different ideas of "good", each a Loom file made of the standard measures
 * and measures of its own: refund (a choice), script author (an artifact, late outcomes from CI)
 * and blog publisher (parameters, careful review, numbers a week later). No Java is written for any
 * of them: this class drives all three through the same engine.
 */
@Tag("L6")
class ReferenceLaddersTest {

    @TempDir Path dir;

    private static List<ScriptValidator.Problem> problems(LadderHarness h) {
        return new ScriptValidator()
                .validate(h.script, new ScriptValidator.Context().registeredTools(Set.of()));
    }

    @Test
    void theThreeReferenceLaddersLoadWithNoErrorsAndNoWarnings() throws IOException {
        for (String name : List.of("refund.loom", "script_author.loom", "blog_publisher.loom")) {
            LadderHarness h = LadderHarness.fromResource(name, dir);
            assertThat(problems(h)).as(name).isEmpty();
            assertThat(h.policy.rungs()).hasSizeGreaterThanOrEqualTo(2);
        }
    }

    // ---------- 1. refund: a choice ----------

    @Test
    void theRefundAgentEarnsActsSlipsAndStartsOverWhenItChanges() throws IOException {
        LadderHarness h = LadderHarness.fromResource("refund.loom", dir);
        LadderEngine e = h.engine;
        assertThat(e.standing("gold", "id-a", "m-1").rungName()).isEqualTo("watch");

        for (int i = 0; i < 9; i++) h.answered("gold", "approve", "approve");
        assertThat(e.afterResponse("gold")).isEmpty(); // nine is not enough
        h.answered("gold", "approve", "approve");
        assertThat(e.afterResponse("gold"))
                .extracting(LadderEngine.Change::kind)
                .containsExactly("proposed");
        assertThat(e.standing("gold", "id-a", "m-1").rungName())
                .isEqualTo("watch"); // waiting for risk-owner
        e.approve("gold", "risk-owner", "numbers look right");
        assertThat(e.standing("gold", "id-a", "m-1").rungName()).isEqualTo("suggest");

        for (int i = 0; i < 20; i++)
            h.answered("gold", "approve", "approve", "suggest", "id-a", 1, Map.of());
        assertThat(e.afterResponse("gold"))
                .extracting(LadderEngine.Change::kind)
                .containsExactly("proposed");
        e.approve("gold", "risk-owner", "30 clean cases");
        assertThat(e.standing("gold", "id-a", "m-1").rungName()).isEqualTo("act");

        // a different scope earns separately
        assertThat(e.standing("basic", "id-a", "m-1").rungName()).isEqualTo("watch");

        // two dangerous mistakes: the agent approved what a person rejected
        h.answered("gold", "approve", "reject", "act", "id-a", 1, Map.of());
        assertThat(e.afterResponse("gold")).isEmpty();
        h.answered("gold", "approve", "reject", "act", "id-a", 1, Map.of());
        List<LadderEngine.Change> slip = e.afterResponse("gold");
        assertThat(slip).extracting(LadderEngine.Change::kind).containsExactly("demoted");
        assertThat(slip.get(0).to()).isEqualTo("suggest");
        assertThat(slip.get(0).reason()).contains("history.last(50).count(dangerous) >= 2");
        assertThat(e.afterResponse("gold"))
                .isEmpty(); // and the dangerous cases keep it from climbing back

        // the model behind the agent changes: a new epoch, back to watch, the old record kept
        LevelLog.Standing fresh = e.standing("gold", "id-b", "m-1");
        assertThat(fresh.epoch()).isEqualTo(2);
        assertThat(fresh.rungName()).isEqualTo("watch");
        assertThat(e.history("gold", 2).cases()).isEmpty();
        assertThat(e.historyAll("gold").cases()).hasSize(32);
        for (int i = 0; i < 10; i++)
            h.answered("gold", "approve", "approve", "watch", "id-b", 2, Map.of());
        assertThat(e.afterResponse("gold"))
                .extracting(LadderEngine.Change::kind)
                .containsExactly("proposed");

        // an amount over 200 goes to a person whatever the rung; frozen holds the agent below
        // autonomy
        e.set("gold", "act", true, "incident drill", "ops");
        LevelLog.Standing act = e.standing("gold", "id-b", "m-1");
        V.Cs history = e.history("gold", 2);
        V.Rec small =
                HistoryBuilder.record(
                        new LCase(
                                "x",
                                "Refund",
                                "gold",
                                LadderHarness.START,
                                "act",
                                "",
                                "id-b",
                                2,
                                1,
                                false,
                                Map.of("amount", "20"),
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
                                List.of()));
        V.Rec big =
                HistoryBuilder.record(
                        new LCase(
                                "y",
                                "Refund",
                                "gold",
                                LadderHarness.START,
                                "act",
                                "",
                                "id-b",
                                2,
                                1,
                                false,
                                Map.of("amount", "900"),
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
                                List.of()));
        assertThat(e.plan("gold", "case-small", act, small, history, h.clock.millis()).mode())
                .isIn(RungDef.Mode.AUTONOMOUS, RungDef.Mode.BLIND);
        assertThat(e.plan("gold", "case-big", act, big, history, h.clock.millis()).how())
                .isEqualTo("escalate");
        e.freeze("incident 42", "ops");
        assertThat(e.plan("gold", "case-small", act, small, history, h.clock.millis()).mode())
                .isEqualTo(RungDef.Mode.CONFIRM);
        e.unfreeze("resolved", "ops");
        assertThat(e.plan("gold", "case-big", act, big, history, h.clock.millis()).how())
                .isEqualTo("escalate");
    }

    // ---------- 2. script author: an artifact, late outcomes from CI ----------

    @Test
    void theScriptAuthorIsJudgedByEditsAndByWhatCiLaterSays() throws IOException {
        LadderHarness h = LadderHarness.fromResource("script_author.loom", dir);
        LadderEngine e = h.engine;
        Map<String, String> repo = Map.of("repo", "payments", "spec_size", "3");
        assertThat(e.standing("payments", "id-a", "m-1").rungName()).isEqualTo("review");

        // reviewers rewrite most of what it writes: it does not climb however long it goes on
        for (int i = 0; i < 12; i++)
            h.artifact(
                    "payments",
                    "def f(): pass",
                    "edit",
                    "def f():\n    return compute(x, y)  # rewritten",
                    "review",
                    "id-a",
                    1,
                    repo,
                    90_000);
        assertThat(e.afterResponse("payments")).isEmpty();

        // a better agent: reviewers barely touch it
        LevelLog.Standing second = e.standing("payments", "id-b", "m-1");
        assertThat(second.epoch()).isEqualTo(2);
        for (int i = 0; i < 7; i++)
            h.artifact(
                    "payments",
                    "def f(): return 1",
                    "accept",
                    "def f(): return 1",
                    "review",
                    "id-b",
                    2,
                    repo,
                    40_000);
        assertThat(e.afterResponse("payments")).isEmpty(); // seven is not eight
        h.artifact(
                "payments",
                "def f(): return 1",
                "edit",
                "def f(): return 1 ",
                "review",
                "id-b",
                2,
                repo,
                40_000);
        assertThat(e.afterResponse("payments"))
                .extracting(LadderEngine.Change::kind)
                .containsExactly("proposed");
        e.approve("payments", "eng-lead", "reviewed the diffs");
        assertThat(e.standing("payments", "id-b", "m-1").rungName()).isEqualTo("publish");

        // it publishes on its own; CI later reverts one of its scripts: slip, and the outcome is in
        // the record
        String published = null;
        for (int i = 0; i < 3; i++)
            published =
                    h.artifact(
                            "payments",
                            "def g(): return 2",
                            "accept",
                            "def g(): return 2",
                            "publish",
                            "id-b",
                            2,
                            repo,
                            0);
        assertThat(e.afterResponse("payments")).isEmpty();
        List<LadderEngine.Change> slip = e.outcome(published, "reverted", null, "build 8812 red");
        assertThat(slip).extracting(LadderEngine.Change::kind).containsExactly("demoted");
        assertThat(slip.get(0).to()).isEqualTo("review");
        assertThat(slip.get(0).reason()).contains("history.last(10).count(broke) >= 1");

        // it cannot climb straight back while that revert is among its last ten; ten clean cases
        // later it can
        for (int i = 0; i < 9; i++)
            h.artifact(
                    "payments",
                    "def h(): return 3",
                    "accept",
                    "def h(): return 3",
                    "review",
                    "id-b",
                    2,
                    repo,
                    40_000);
        assertThat(e.afterResponse("payments")).isEmpty();
        h.artifact(
                "payments",
                "def h(): return 3",
                "accept",
                "def h(): return 3",
                "review",
                "id-b",
                2,
                repo,
                40_000);
        assertThat(e.afterResponse("payments"))
                .extracting(LadderEngine.Change::kind)
                .containsExactly("proposed");
    }

    // ---------- 3. blog publisher: parameters, care, numbers a week later ----------

    @Test
    void theBlogPublisherNeedsCarefulReviewsAndTrafficBeforeItPublishesAlone() throws IOException {
        LadderHarness h = LadderHarness.fromResource("blog_publisher.loom", dir);
        LadderEngine e = h.engine;
        Map<String, String> tech = Map.of("topic", "tools", "section", "tech", "word_count", "900");
        assertThat(e.standing("tech", "id-a", "m-1").rungName()).isEqualTo("edit");

        // six careful, untouched reviews: but nobody knows yet how the posts did, and not knowing
        // is not a pass
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++)
            ids.add(
                    h.artifact(
                            "tech", "A post", "accept", "A post", "edit", "id-a", 1, tech, 45_000));
        assertThat(e.afterResponse("tech")).isEmpty();
        for (String id : ids)
            e.outcome(
                    id,
                    "views_7d",
                    1500,
                    null); // a week later: each arrival is looked at, and the last one tips it
        assertThat(e.openProposal("tech")).isPresent();
        e.approve("tech", "editor-in-chief", "good run");
        assertThat(e.standing("tech", "id-a", "m-1").rungName()).isEqualTo("publish");

        // a take-down is the end of it
        String last =
                h.artifact(
                        "tech",
                        "A risky post",
                        "accept",
                        "A risky post",
                        "publish",
                        "id-a",
                        1,
                        tech,
                        0);
        List<LadderEngine.Change> slip = e.outcome(last, "status", "taken down", "legal request");
        assertThat(slip).extracting(LadderEngine.Change::kind).containsExactly("demoted");
        assertThat(slip.get(0).to()).isEqualTo("edit");

        // topics that need a lawyer always go to a person
        e.set("tech", "publish", true, "drill", "ops");
        LevelLog.Standing s = e.standing("tech", "id-a", "m-1");
        LCase legal =
                new LCase(
                        "z",
                        "Publish",
                        "tech",
                        LadderHarness.START,
                        "publish",
                        "",
                        "id-a",
                        1,
                        1,
                        false,
                        Map.of("topic", "legal"),
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
                                        "tech",
                                        "z",
                                        s,
                                        HistoryBuilder.record(legal),
                                        e.history("tech", 1),
                                        h.clock.millis())
                                .how())
                .isEqualTo("escalate");
    }

    @Test
    void aRubberStampingReviewerDoesNotEarnTheAgentTheRightToPublish() throws IOException {
        LadderHarness h = LadderHarness.fromResource("blog_publisher.loom", dir);
        LadderEngine e = h.engine;
        Map<String, String> tech = Map.of("topic", "tools", "section", "tech", "word_count", "900");
        e.standing("tech", "id-a", "m-1");
        for (int i = 0; i < 40; i++) {
            String id =
                    h.artifact(
                            "tech", "A post", "accept", "A post", "edit", "id-a", 1, tech,
                            2_000); // approved in two seconds
            e.outcome(id, "views_7d", 5000, null);
        }
        assertThat(e.afterResponse("tech")).isEmpty();
        assertThat(e.history("tech", 1).cases()).hasSize(40);
        assertThat(h.policy.explainClimb(1, e.history("tech", 1), h.clock.millis()).get(0).actual())
                .isEqualTo("0"); // careful reviews: none
    }

    @Test
    void anEditorsPolicyOnTheBaselineChangesWhatCountsAsEnoughTraffic() throws IOException {
        LadderHarness h = LadderHarness.fromResource("blog_publisher.loom", dir);
        LadderEngine e = h.engine;
        Map<String, String> tech = Map.of("topic", "tools", "section", "tech", "word_count", "900");
        e.standing("tech", "id-a", "m-1");
        for (int i = 0; i < 6; i++) {
            String id =
                    h.artifact(
                            "tech", "A post", "accept", "A post", "edit", "id-a", 1, tech, 45_000);
            e.outcome(id, "views_7d", 800, null); // under 80% of a baseline of 1200
        }
        assertThat(e.afterResponse("tech")).isEmpty();
        LadderEngine lowerBar = h.restart(h.edited("baseline_views: 1200", "baseline_views: 900"));
        assertThat(lowerBar.afterResponse("tech"))
                .extracting(LadderEngine.Change::kind)
                .containsExactly("proposed");
    }

    // ---------- the rules change ----------

    @Test
    void tighteningAReferenceLadderDemotesAndLooseningNeverPromotes() throws IOException {
        LadderHarness h = LadderHarness.fromResource("refund.loom", dir);
        LadderEngine e = h.engine;
        e.standing("gold", "id-a", "m-1");
        for (int i = 0; i < 10; i++) h.answered("gold", "approve", "approve");
        e.afterResponse("gold");
        e.approve("gold", "risk-owner", "ok");
        assertThat(e.standing("gold", "id-a", "m-1").rungName()).isEqualTo("suggest");

        LadderEngine tighter =
                h.restart(h.edited("history.count >= 10 and", "history.count >= 25 and"));
        List<LadderEngine.Change> changes = tighter.reevaluate();
        assertThat(changes).extracting(LadderEngine.Change::kind).containsExactly("demoted");
        assertThat(changes.get(0).reason())
                .contains("rules changed")
                .contains("history.count >= 25");
        assertThat(tighter.standing("gold", "id-a", "m-1").rungName()).isEqualTo("watch");

        LadderEngine looser =
                h.restart(h.edited("history.count >= 10 and", "history.count >= 2 and"));
        assertThat(looser.reevaluate()).isEmpty();
        assertThat(looser.standing("gold", "id-a", "m-1").rungName()).isEqualTo("watch");
        assertThat(
                        h.ledger.recordsOfKind("Refund", Rec.LEVEL).stream()
                                .filter(
                                        r ->
                                                "rules".equals(r.str("kind"))
                                                        || "demoted".equals(r.str("kind")))
                                .count())
                .isGreaterThanOrEqualTo(2);
    }
}
