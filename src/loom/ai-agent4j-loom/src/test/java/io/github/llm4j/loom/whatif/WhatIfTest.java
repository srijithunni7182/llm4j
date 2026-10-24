package io.github.llm4j.loom.whatif;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.explain.Why;
import io.github.llm4j.loom.travel.TravelFixtures;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R25 to R27 of spec 13: change one thing in a past run and see how it would have gone. */
class WhatIfTest {

    @TempDir Path dir;

    static WhatIf.Request request(
            TravelFixtures.Run real, Why.Selector at, List<WhatIf.Change> changes, String models) {
        return new WhatIf.Request(
                real.script(),
                real.source().workflow(),
                real.source().input(),
                real.run().journal(),
                at,
                changes,
                models,
                null,
                Set.of());
    }

    private TravelFixtures.Run run(String name) throws Exception {
        var c =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals(name))
                        .findFirst()
                        .orElseThrow();
        return TravelFixtures.run(dir, c);
    }

    @Test
    void aDifferentClassifierAnswerSendsTheRunDownTheOtherBranchForFree() throws Exception {
        var real = run("triage order status");
        var change =
                new WhatIf.Change(
                        "answer", "Intent", "{\"kind\":\"refund\",\"urgent\":\"yes\"}", 1);
        WhatIf.Outcome o =
                WhatIf.run(
                        request(
                                real,
                                new Why.Selector(null, null, 8),
                                List.of(change),
                                "recorded"));
        assertThat(o.at()).isEqualTo("Main/s0");
        assertThat(o.atStatement()).isEqualTo("classify Intent");
        assertThat(o.candidate().status()).isEqualTo("completed");
        assertThat(o.candidateResult()).isEqualTo("retention");
        assertThat(o.originalResult()).isEqualTo("it is on its way");
        assertThat(o.diff().firstDifference())
                .contains(
                        "alt (intent.urgent == \"yes\") went else in the original and then in the candidate")
                .contains("intent.urgent was \"no\", now \"yes\"");
        assertThat(o.diff().onlyOriginal()).anyMatch(s -> s.startsWith("delegate Replier"));
        assertThat(o.diff().both()).anyMatch(s -> s.startsWith("classify Intent"));
        assertThat(o.need()).isNull();
    }

    @Test
    void aVariableSetAtTheStepChangesWhatFollows() throws Exception {
        var real = run("human answer");
        // the person said "yes"; what if the approval had been "no"
        var change = new WhatIf.Change("human", "", "no", 1);
        WhatIf.Outcome o =
                WhatIf.run(
                        request(
                                real,
                                new Why.Selector(null, null, 4),
                                List.of(change),
                                "recorded"));
        assertThat(o.candidateResult()).isEqualTo("rejected");
        assertThat(o.originalResult()).isEqualTo("published");
        assertThat(o.diff().firstDifference()).contains("alt (approval == \"yes\")");
    }

    @Test
    void whenAnEarlierChangeFlowsIntoAModelQuestionTheCandidateStopsAndSaysSo() throws Exception {
        var real = run("triage order status");
        // the classifier still says order_status, but the message the agent is asked about changed
        var change = new WhatIf.Change("set", "message", "where is my REFUND?", 1);
        WhatIf.Outcome o =
                WhatIf.run(
                        request(
                                real,
                                new Why.Selector(null, null, 8),
                                List.of(change),
                                "recorded"));
        assertThat(o.candidate().status()).isEqualTo("needs");
        assertThat(o.need()).isNotNull();
        assertThat(o.need().what()).isEqualTo("a classifier answer from Intent");
        assertThat(o.need().because())
                .contains("its task text changed")
                .contains("where is my REFUND?");
        // nothing was invented: no result
        assertThat(o.candidateResult()).isNull();
    }

    @Test
    void aRecordedAnswerIsReusedWhenNothingItDependsOnChanged() throws Exception {
        var real = run("triage order status");
        // change only what comes after the delegate's own question
        var change =
                new WhatIf.Change(
                        "answer", "Intent", "{\"kind\":\"order_status\",\"urgent\":\"no\"}", 1);
        WhatIf.Outcome o =
                WhatIf.run(
                        request(
                                real,
                                new Why.Selector(null, null, 8),
                                List.of(change),
                                "recorded"));
        // the delegate's question is the same, so its recorded reply is reused
        assertThat(o.candidate().status()).isEqualTo("completed");
        assertThat(o.candidateResult()).isEqualTo("it is on its way");
        assertThat(o.diff().firstDifference()).isNull();
    }

    @Test
    void scriptedModelsReuseNothing() throws Exception {
        var real = run("triage order status");
        var change =
                new WhatIf.Change(
                        "answer", "Intent", "{\"kind\":\"order_status\",\"urgent\":\"no\"}", 1);
        WhatIf.Outcome o =
                WhatIf.run(
                        request(
                                real,
                                new Why.Selector(null, null, 8),
                                List.of(change),
                                "scripted"));
        assertThat(o.candidate().status()).isEqualTo("needs");
        assertThat(o.need().what()).isEqualTo("a model answer from Replier");
    }

    @Test
    void anInjectedFailureRunsTheScriptsOwnHandling() throws Exception {
        var real = run("failure taken over");
        var change = new WhatIf.Change("fail", "A", "rate_limit", 1);
        WhatIf.Outcome o =
                WhatIf.run(
                        request(
                                real,
                                new Why.Selector(null, null, 3),
                                List.of(change),
                                "recorded"));
        // the model fails once and the step has one retry: the second call is asked for, and a
        // model is not guessed
        assertThat(o.candidate().status()).isIn("needs", "completed");
        assertThat(o.candidate().steps()).contains("Main/s0");
    }

    @Test
    void theEffectsAnAgentMayCauseAreComparedAndANewOneIsUnsafe() throws Exception {
        var script =
                """
                tool Mailer { use: email outbox: "o" from: "a@x.com" to: "b@x.com" }
                intentclassifier Intent {
                    model: "m"
                    choose kind: ok | bad
                }
                agent Notifier { model: "m" system: "x" tools: [Mailer] }
                agent Quiet { model: "m" system: "x" }
                workflow Main(message) {
                    intentclassify Intent on message -> intent
                    alt (intent.kind == "bad") {
                        delegate "Tell them {message}" to Notifier -> sent
                    } else {
                        delegate "Note {message}" to Quiet -> sent
                    }
                    return sent
                }
                """;
        var c =
                new TravelFixtures.Case(
                        "mailer",
                        script,
                        "Main",
                        java.util.Map.of("message", "hi"),
                        t ->
                                t.classifier("Intent", java.util.Map.of("kind", "ok"))
                                        .model("Quiet", "noted"));
        var real = TravelFixtures.run(dir, c);
        var change = new WhatIf.Change("answer", "Intent", "{\"kind\":\"bad\"}", 1);
        WhatIf.Outcome o =
                WhatIf.run(
                        request(
                                real,
                                new Why.Selector(null, null, 9),
                                List.of(change),
                                "recorded"));
        assertThat(o.effects().unsafe()).isTrue();
        assertThat(o.effects().onlyCandidate())
                .singleElement()
                .satisfies(
                        e -> {
                            assertThat(e.via()).isEqualTo("Mailer (email)");
                            assertThat(e.statement()).isEqualTo("delegate Notifier");
                        });
        assertThat(o.effects().onlyOriginal()).isEmpty();
    }

    @Test
    void aStatementThatNeverRanHasNoMomentToGoBackTo() throws Exception {
        var real = run("triage order status");
        assertThatThrownBy(
                        () ->
                                WhatIf.run(
                                        request(
                                                real,
                                                new Why.Selector(null, null, 10),
                                                List.of(),
                                                "recorded")))
                .hasMessageContaining("never ran in this run");
    }

    @Test
    void anAnswerThatIsNotAPossibleLabelIsRefusedWithTheLabels() throws Exception {
        var real = run("triage order status");
        var change =
                new WhatIf.Change("answer", "Intent", "{\"kind\":\"banana\",\"urgent\":\"no\"}", 1);
        assertThatThrownBy(
                        () ->
                                WhatIf.run(
                                        request(
                                                real,
                                                new Why.Selector(null, null, 8),
                                                List.of(change),
                                                "recorded")))
                .hasMessageContaining(
                        "kind of Intent can be order_status, refund, other, not banana");
        assertThatThrownBy(
                        () ->
                                WhatIf.run(
                                        request(
                                                real,
                                                new Why.Selector(null, null, 8),
                                                List.of(
                                                        new WhatIf.Change(
                                                                "answer", "Ghost", "x", 1)),
                                                "recorded")))
                .hasMessageContaining("not an agent or a classifier");
    }

    @Test
    void anUnknownFailureCategoryIsRefused() throws Exception {
        var real = run("straight");
        assertThatThrownBy(
                        () ->
                                WhatIf.run(
                                        request(
                                                real,
                                                new Why.Selector(null, null, 3),
                                                List.of(
                                                        new WhatIf.Change(
                                                                "fail", "A", "gremlins", 1)),
                                                "recorded")))
                .hasMessageContaining("Unknown failure category");
    }

    @Test
    void theOriginalJournalIsNeverWritten() throws Exception {
        var real = run("triage order status");
        var before = real.run().journal().all();
        WhatIf.run(
                request(
                        real,
                        new Why.Selector(null, null, 8),
                        List.of(
                                new WhatIf.Change(
                                        "answer",
                                        "Intent",
                                        "{\"kind\":\"refund\",\"urgent\":\"yes\"}",
                                        1)),
                        "recorded"));
        assertThat(real.run().journal().all()).isEqualTo(before);
    }
}
