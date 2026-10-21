package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ContextValuesTest {

    @Test
    void aWindowHasAUsableInputSize() {
        ContextWindow w = ContextWindow.of(128_000).reservingForOutput(8_000);
        assertThat(w.tokens()).isEqualTo(128_000);
        assertThat(w.reservedForOutput()).isEqualTo(8_000);
        assertThat(w.usableInput()).isEqualTo(120_000);
        assertThat(ContextWindow.of(1_000).usableInput()).isEqualTo(1_000);
    }

    @Test
    void aWindowMustMakeSense() {
        assertThatThrownBy(() -> ContextWindow.of(0)).hasMessageContaining("tokens");
        assertThatThrownBy(() -> ContextWindow.of(-5)).hasMessageContaining("tokens");
        assertThatThrownBy(() -> ContextWindow.of(1_000).reservingForOutput(-1))
                .hasMessageContaining("reservedForOutput");
        assertThatThrownBy(() -> ContextWindow.of(1_000).reservingForOutput(1_000))
                .hasMessageContaining("reservedForOutput");
    }

    @Test
    void usageIsAFractionOfTheUsableWindow() {
        ContextWindow w = ContextWindow.of(10_000).reservingForOutput(2_000);
        ContextUsage u = new ContextUsage(4_000, w, ContextUsage.Source.REPORTED);
        assertThat(u.fraction().getAsDouble()).isEqualTo(0.5);
        assertThat(
                        new ContextUsage(9_000, w, ContextUsage.Source.ESTIMATED)
                                .fraction()
                                .getAsDouble())
                .isGreaterThan(1.0);
        assertThat(new ContextUsage(4_000, null, ContextUsage.Source.ESTIMATED).fraction())
                .isEmpty();
        assertThatThrownBy(() -> new ContextUsage(-1, w, ContextUsage.Source.REPORTED))
                .hasMessageContaining("used");
    }

    @Test
    void thePolicyHasSensibleDefaults() {
        ContextPolicy p = ContextPolicy.builder().build();
        assertThat(p.window()).isNull();
        assertThat(p.triggerAt()).isEqualTo(0.80);
        assertThat(p.targetAt()).isEqualTo(0.50);
        assertThat(p.keepRecent()).isEqualTo(4);
        assertThat(p.maxCompactions()).isEqualTo(8);
        assertThat(p.minGain()).isEqualTo(0.10);
        assertThat(p.onOverflow()).isEqualTo(ContextPolicy.OnOverflow.RETRY_ONCE);
        assertThat(p.pinned().test(io.github.llm4j.model.Message.user("x"))).isFalse();
    }

    @Test
    void thePolicyIsConfiguredWithABuilder() {
        ContextPolicy p =
                ContextPolicy.builder()
                        .window(ContextWindow.of(200_000).reservingForOutput(8_000))
                        .triggerAt(0.75)
                        .targetAt(0.40)
                        .keepRecent(6)
                        .maxCompactions(3)
                        .minGain(0.2)
                        .onOverflow(ContextPolicy.OnOverflow.FAIL)
                        .pinned(Messages::isPinned)
                        .build();
        assertThat(p.window().usableInput()).isEqualTo(192_000);
        assertThat(p.triggerAt()).isEqualTo(0.75);
        assertThat(p.targetAt()).isEqualTo(0.40);
        assertThat(p.keepRecent()).isEqualTo(6);
        assertThat(p.maxCompactions()).isEqualTo(3);
        assertThat(p.minGain()).isEqualTo(0.2);
        assertThat(p.onOverflow()).isEqualTo(ContextPolicy.OnOverflow.FAIL);
        assertThat(p.pinned().test(Messages.pinned(io.github.llm4j.model.Message.user("x"))))
                .isTrue();
    }

    @Test
    void thePolicyRejectsNonsense() {
        assertThatThrownBy(() -> ContextPolicy.builder().triggerAt(0).build())
                .hasMessageContaining("triggerAt");
        assertThatThrownBy(() -> ContextPolicy.builder().triggerAt(1.5).build())
                .hasMessageContaining("triggerAt");
        assertThatThrownBy(() -> ContextPolicy.builder().targetAt(0).build())
                .hasMessageContaining("targetAt");
        assertThatThrownBy(() -> ContextPolicy.builder().triggerAt(0.5).targetAt(0.5).build())
                .hasMessageContaining("targetAt must be below triggerAt");
        assertThatThrownBy(() -> ContextPolicy.builder().keepRecent(-1).build())
                .hasMessageContaining("keepRecent");
        assertThatThrownBy(() -> ContextPolicy.builder().maxCompactions(-1).build())
                .hasMessageContaining("maxCompactions");
        assertThatThrownBy(() -> ContextPolicy.builder().minGain(1.0).build())
                .hasMessageContaining("minGain");
        assertThatThrownBy(() -> ContextPolicy.builder().minGain(-0.1).build())
                .hasMessageContaining("minGain");
    }

    @Test
    void aPolicyAsksForATargetInTokens() {
        ContextPolicy p =
                ContextPolicy.builder()
                        .window(ContextWindow.of(10_000).reservingForOutput(2_000))
                        .triggerAt(0.75)
                        .targetAt(0.5)
                        .build();
        assertThat(p.triggerTokens()).hasValue(6_000L);
        assertThat(p.targetTokens()).hasValue(4_000L);
        assertThat(ContextPolicy.builder().build().targetTokens()).isEmpty();
    }

    @Test
    void aRequestCarriesWhatACompactorNeeds() {
        CompactionRequest r = CompactionRequest.of(500);
        assertThat(r.targetTokens()).isEqualTo(500);
        assertThat(r.reason()).isEqualTo(CompactionRequest.Reason.MANUAL);
        assertThat(r.keepRecent()).isEqualTo(4);
        assertThat(r.estimator()).isNotNull();
        assertThat(r.pinned().test(io.github.llm4j.model.Message.user("x"))).isFalse();
        assertThat(r.protectTask()).isTrue();
        assertThat(CompactionRequest.builder(10).protectTask(false).build().protectTask())
                .isFalse();
        assertThat(r.sizeOf(java.util.List.of())).isZero();
        assertThat(r.sizeOf(java.util.List.of(io.github.llm4j.model.Message.user("x".repeat(400)))))
                .isGreaterThan(90);
        assertThatThrownBy(() -> CompactionRequest.of(0)).hasMessageContaining("targetTokens");
        assertThatThrownBy(() -> CompactionRequest.builder(10).keepRecent(-1).build())
                .hasMessageContaining("keepRecent");
    }

    @Test
    void aRequestCanBeBuiltFromAPolicy() {
        ContextPolicy p =
                ContextPolicy.builder()
                        .window(ContextWindow.of(10_000))
                        .keepRecent(7)
                        .pinned(Messages::isPinned)
                        .build();
        CompactionRequest r = CompactionRequest.from(p, 3_000, CompactionRequest.Reason.THRESHOLD);
        assertThat(r.targetTokens()).isEqualTo(3_000);
        assertThat(r.keepRecent()).isEqualTo(7);
        assertThat(r.reason()).isEqualTo(CompactionRequest.Reason.THRESHOLD);
        assertThat(r.window()).isEqualTo(p.window());
        assertThat(r.pinned().test(Messages.pinned(io.github.llm4j.model.Message.user("x"))))
                .isTrue();
    }

    @Test
    void aMessageMarkedAsPinnedIsKeptWithoutAnyExtraConfiguration() {
        io.github.llm4j.model.Message marked =
                Messages.pinned(io.github.llm4j.model.Message.user("keep me"));
        io.github.llm4j.model.Message plain =
                io.github.llm4j.model.Message.user("DECISION: use plan A");
        assertThat(ContextPolicy.builder().build().pinned().test(marked)).isTrue();
        assertThat(CompactionRequest.of(10).pinned().test(marked)).isTrue();
        assertThat(CompactionRequest.of(10).pinned().test(plain)).isFalse();
    }

    @Test
    void aTestOfYourOwnAddsToTheMarkerNeverReplacesIt() {
        io.github.llm4j.model.Message marked =
                Messages.pinned(io.github.llm4j.model.Message.user("keep me"));
        io.github.llm4j.model.Message decision =
                io.github.llm4j.model.Message.user("DECISION: use plan A");
        io.github.llm4j.model.Message other = io.github.llm4j.model.Message.user("chatter");
        java.util.function.Predicate<io.github.llm4j.model.Message> own =
                m -> m.getContent().startsWith("DECISION:");
        for (java.util.function.Predicate<io.github.llm4j.model.Message> p :
                java.util.List.<java.util.function.Predicate<io.github.llm4j.model.Message>>of(
                        ContextPolicy.builder().pinned(own).build().pinned(),
                        CompactionRequest.builder(10).pinned(own).build().pinned())) {
            assertThat(p.test(marked)).isTrue();
            assertThat(p.test(decision)).isTrue();
            assertThat(p.test(other)).isFalse();
        }
        assertThat(
                        ContextPolicy.builder()
                                .pinned(own)
                                .build()
                                .withWindow(ContextWindow.of(1_000))
                                .pinned()
                                .test(marked))
                .isTrue();
    }
}
