package io.github.llm4j.loom.whatif;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.explain.Why;
import io.github.llm4j.loom.travel.ObserveReplay;
import io.github.llm4j.loom.travel.TravelFixtures;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R28 to R30 of spec 13: a candidate script, prompt or model, and live models under a cap. */
public class WhatIfCandidateTest {

    @TempDir Path dir;

    /** Answers every call with the same text and 15 tokens, and keeps what it was asked. */
    public static final class FakeModels implements LLMClientFactory {
        public final List<String> models = Collections.synchronizedList(new ArrayList<>());
        public final List<String> systems = Collections.synchronizedList(new ArrayList<>());

        @Override
        public LLMClient createClient(String model) {
            return new LLMClient() {
                @Override
                public LLMResponse chat(LLMRequest request) {
                    models.add(model);
                    systems.add(request.getMessages().get(0).getContent());
                    return LLMResponse.builder()
                            .content("a live reply")
                            .model(model)
                            .tokenUsage(10, 5, 15)
                            .build();
                }

                @Override
                public Stream<LLMResponse> chatStream(LLMRequest request) {
                    return Stream.of(chat(request));
                }
            };
        }
    }

    private TravelFixtures.Run triage() throws Exception {
        var c =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals("triage order status"))
                        .findFirst()
                        .orElseThrow();
        return TravelFixtures.run(dir, c);
    }

    private WhatIf.Request request(
            TravelFixtures.Run real,
            String models,
            Path candidate,
            Map<String, String> prompts,
            Map<String, String> modelEdits,
            ObserveReplay.Live live) {
        return new WhatIf.Request(
                real.script(),
                real.source().workflow(),
                real.source().input(),
                real.run().journal(),
                new Why.Selector(null, null, 8),
                List.of(),
                models,
                null,
                Set.of(),
                candidate,
                prompts,
                modelEdits,
                live);
    }

    private static ObserveReplay.Live live(FakeModels models, Long maxTokens) {
        return new ObserveReplay.Live(models, maxTokens, null, null, k -> null);
    }

    @Test
    void anEditedScriptRunsForwardAndTheTwoHashesDiffer() throws Exception {
        var real = triage();
        Path edited = dir.resolve("edited.loom");
        Files.writeString(
                edited, Files.readString(real.script()).replace("\"retention\"", "\"keep\""));
        // the urgent branch is now different text; take it with a classifier answer
        var urgent =
                new WhatIf.Change(
                        "answer", "Intent", "{\"kind\":\"refund\",\"urgent\":\"yes\"}", 1);
        WhatIf.Request base = request(real, "recorded", edited, null, null, null);
        WhatIf.Outcome o =
                WhatIf.run(
                        new WhatIf.Request(
                                base.script(),
                                base.workflow(),
                                base.inputs(),
                                base.journal(),
                                base.at(),
                                List.of(urgent),
                                "recorded",
                                null,
                                Set.of(),
                                edited,
                                null,
                                null,
                                null));
        assertThat(o.candidateResult()).isEqualTo("keep");
        assertThat(o.originalResult()).isEqualTo("it is on its way");
        assertThat(o.candidateHash()).isNotEqualTo(o.originalHash());
        assertThat(WhatIfRenderer.text(o)).contains("the candidate differs from the one that ran");
        assertThat(WhatIfRenderer.json(o)).contains("\"scripts\"");
    }

    @Test
    void aChangedPromptMeansTheRecordedAnswerIsNotTheAnswerToThisQuestion() throws Exception {
        var real = triage();
        WhatIf.Outcome o =
                WhatIf.run(
                        request(
                                real,
                                "recorded",
                                null,
                                Map.of("Replier", "Reply in rhyme."),
                                null,
                                null));
        assertThat(o.candidate().status()).isEqualTo("needs");
        assertThat(o.need().because()).contains("model or prompt changed");
        assertThat(o.candidateResult()).isNull();
    }

    @Test
    void aChangedModelIsNotTheSameQuestionEither() throws Exception {
        var real = triage();
        WhatIf.Outcome o =
                WhatIf.run(
                        request(
                                real,
                                "recorded",
                                null,
                                null,
                                Map.of("Replier", "other-model"),
                                null));
        assertThat(o.candidate().status()).isEqualTo("needs");
        assertThat(o.need().because()).contains("model or prompt changed");
    }

    @Test
    void anEditNamingNoAgentIsRefused() throws Exception {
        var real = triage();
        assertThatThrownBy(
                        () ->
                                WhatIf.run(
                                        request(
                                                real,
                                                "recorded",
                                                null,
                                                null,
                                                Map.of("Nobody", "m"),
                                                null)))
                .hasMessageContaining("Nobody")
                .hasMessageContaining("not an agent");
    }

    @Test
    void aVersionOfAPromptFileNeedsAgentThatHasOne() throws Exception {
        var real = triage();
        assertThatThrownBy(
                        () ->
                                WhatIf.run(
                                        request(
                                                real,
                                                "recorded",
                                                null,
                                                Map.of("Replier", "@v2"),
                                                null,
                                                null)))
                .hasMessageContaining("no prompt: file");
    }

    @Test
    void liveModelsAnswerWhatNoRecordingCanAndAreCounted() throws Exception {
        var real = triage();
        FakeModels fake = new FakeModels();
        WhatIf.Outcome o =
                WhatIf.run(
                        request(
                                real,
                                "live",
                                null,
                                Map.of("Replier", "Reply in rhyme."),
                                Map.of("Replier", "bigger-model"),
                                live(fake, 1000L)));
        assertThat(o.candidate().status()).isEqualTo("completed");
        assertThat(o.candidateResult()).isEqualTo("a live reply");
        assertThat(o.candidate().calls()).isEqualTo(1);
        assertThat(o.candidate().tokens()).isEqualTo(15);
        assertThat(fake.models).containsExactly("bigger-model");
        assertThat(fake.systems.get(0)).contains("Reply in rhyme.");
        assertThat(o.notes()).anyMatch(n -> n.contains("1 calls (15 tokens)"));
        assertThat(WhatIfRenderer.json(o)).contains("\"spend\"");
        // the original run is as it was
        assertThat(o.originalResult()).isEqualTo("it is on its way");
    }

    @Test
    void liveModelsAreNotCalledWhereTheRecordingStillAnswersTheSameQuestion() throws Exception {
        var real = triage();
        FakeModels fake = new FakeModels();
        WhatIf.Outcome o = WhatIf.run(request(real, "live", null, null, null, live(fake, 1000L)));
        assertThat(o.candidate().status()).isEqualTo("completed");
        assertThat(o.candidateResult()).isEqualTo("it is on its way");
        assertThat(fake.models).isEmpty();
        assertThat(o.candidate().calls()).isZero();
    }

    @Test
    void theSpendCapStopsTheRunWithoutAResult() throws Exception {
        var real = triage();
        FakeModels fake = new FakeModels();
        WhatIf.Outcome o =
                WhatIf.run(
                        request(
                                real,
                                "live",
                                null,
                                null,
                                Map.of("Replier", "bigger-model"),
                                live(fake, 5L)));
        assertThat(o.candidate().status()).isEqualTo("budget");
        assertThat(o.candidateResult()).isNull();
        assertThat(o.notes()).anyMatch(n -> n.contains("spend cap"));
    }

    @Test
    void liveNeedsModelsToCall() throws Exception {
        var real = triage();
        assertThatThrownBy(() -> WhatIf.run(request(real, "live", null, null, null, null)))
                .hasMessageContaining("--models live needs");
    }
}
