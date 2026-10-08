package io.github.llm4j.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.ratelimit.Sleeper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The small contracts that side-effect tools and their hosts share. */
class EffectContractsTest {

    @Test
    void anInMemoryJournalStoresAndListsEntries() {
        EffectJournal journal = EffectJournal.inMemory();
        assertThat(journal.get("k")).isEmpty();
        journal.put("k", new EffectJournal.Entry("effect_done", "sent"));
        assertThat(journal.get("k")).contains(new EffectJournal.Entry("effect_done", "sent"));
        assertThat(journal.all()).containsOnlyKeys("k");
        journal.put("k", new EffectJournal.Entry("effect_failed", "no"));
        assertThat(journal.get("k").orElseThrow().kind()).isEqualTo("effect_failed");
    }

    @Test
    void aNoopContextRecordsNothingButHasAWorkingJournalAndClock() {
        EffectContext context = EffectContext.noop();
        context.audit("event", Map.of());
        context.trace("text", Map.of());
        assertThat(context.currentStep()).isEmpty();
        assertThat(context.attempt()).isZero();
        assertThat(context.reservedPaths()).isEmpty();
        assertThat(context.sleeper()).isSameAs(Sleeper.SYSTEM);
        assertThat(context.clock()).isEqualTo(Clock.systemUTC());
        context.journal().put("k", new EffectJournal.Entry("x", "y"));
        assertThat(context.journal().get("k")).isPresent();
    }

    @Test
    void aHostThatCannotRunAStepAgainIdentifiesItByItsCurrentStepAndIsNotSimulating() {
        EffectContext context = EffectContext.noop();
        assertThat(context.identityStep()).isEqualTo(context.currentStep());
        assertThat(context.simulate()).isFalse();
    }

    @Test
    void outcomesCarryTheirStatusAndErrorsAreMarkedAsSuch() {
        assertThat(Outcome.ok("done")).isEqualTo(new Outcome("done", Outcome.Status.OK));
        assertThat(Outcome.failed("refused").text()).isEqualTo("Error: refused");
        assertThat(Outcome.failed("refused").status()).isEqualTo(Outcome.Status.FAILED);
        assertThat(Outcome.unknown("lost").status()).isEqualTo(Outcome.Status.UNKNOWN);
    }

    @Test
    void thePolicyDefaultsToSkippingAnUnknownEarlierAttempt() {
        assertThat(EffectPolicy.DEFAULT.onUnknown()).isEqualTo(EffectPolicy.OnUnknown.SKIP);
        assertThat(EffectPolicy.DEFAULT.idempotent()).isFalse();
        assertThat(EffectPolicy.DEFAULT.maxPerRun()).isZero();
        assertThat(EffectPolicy.parse("retry")).isEqualTo(EffectPolicy.OnUnknown.RETRY);
        assertThat(EffectPolicy.parse("skip")).isEqualTo(EffectPolicy.OnUnknown.SKIP);
        assertThat(EffectPolicy.parse(null)).isEqualTo(EffectPolicy.OnUnknown.SKIP);
    }

    @Test
    void aKindIsOptionalAboutEverythingExceptItsNameCheckAndCreate() throws Exception {
        ToolKind kind = new ToolKind() {
            @Override public String name() { return "demo"; }
            @Override public String check(Map<String, String> options, Path baseDir) { return null; }
            @Override public io.github.llm4j.agent.Tool create(String name, Map<String, String> options, Path baseDir) { return null; }
            @Override public io.github.llm4j.agent.Tool create(String name, Map<String, String> options, Path baseDir, EffectContext context) { return null; }
        };
        assertThat(kind.required()).isEmpty();
        assertThat(kind.optional()).isEmpty();
        assertThat(kind.prefixes()).isEmpty();
        assertThat(kind.secrets()).isEmpty();
        assertThat(kind.agentProblem(Map.of(), "T", "A", false)).isNull();
    }
}
