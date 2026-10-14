package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.budget.CharsPerTokenEstimator;
import io.github.llm4j.model.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The decisions and guards of the manager (design §4.4): R5, R6, R9, R10, R11. */
class ContextManagerTest {

    static final class Recorder implements ContextEvents {
        final List<ContextUsage> usages = new ArrayList<>();
        final List<CompactionEvent> compactions = new ArrayList<>();

        @Override
        public void onUsage(ContextUsage usage) {
            usages.add(usage);
        }

        @Override
        public void onCompaction(CompactionEvent event) {
            compactions.add(event);
        }
    }

    private static ContextPolicy policy(int window, double trigger, double target) {
        return ContextPolicy.builder().window(ContextWindow.of(window)).triggerAt(trigger).targetAt(target).keepRecent(2).build();
    }

    private static List<Message> convo(int steps, int chars) {
        Convo c = new Convo().system("sys").task("task");
        for (int i = 0; i < steps; i++) c.step("search", chars);
        return c.build();
    }

    private static long size(List<Message> m) {
        return CompactionRequest.of(1).sizeOf(m);
    }

    private static ContextManager manager(ContextPolicy p, ContextCompactor c, Recorder r) {
        return new ContextManager(p, c, CharsPerTokenEstimator.INSTANCE, 0, r);
    }

    @Test
    void withoutAWindowItReportsUsageAndNeverCompacts() {
        Recorder r = new Recorder();
        AtomicInteger called = new AtomicInteger();
        ContextCompactor c = (m, q) -> {
            called.incrementAndGet();
            return CompactionResult.unchanged("x", m, q, CompactionResult.Outcome.SKIPPED, "");
        };
        ContextManager mgr = manager(ContextPolicy.builder().build(), c, r);
        ContextManager.Prepared p = mgr.prepare(convo(20, 2_000), 0);
        assertThat(p.changed()).isFalse();
        assertThat(called).hasValue(0);
        assertThat(r.usages).hasSize(1);
        assertThat(r.usages.get(0).window()).isNull();
        assertThat(r.usages.get(0).fraction()).isEmpty();
        assertThat(r.usages.get(0).source()).isEqualTo(ContextUsage.Source.ESTIMATED);
    }

    @Test
    void withoutACompactorItOnlyReportsUsage() {
        Recorder r = new Recorder();
        List<Message> in = convo(8, 400);
        ContextManager mgr = manager(policy((int) (size(in) * 1.1), 0.8, 0.5), null, r);
        ContextManager.Prepared p = mgr.prepare(in, 0);
        assertThat(p.changed()).isFalse();
        assertThat(r.usages.get(0).fraction().getAsDouble()).isGreaterThan(0.9);
        assertThat(r.compactions).isEmpty();
    }

    @Test
    void belowTheTriggerItDoesNotCompact() {
        Recorder r = new Recorder();
        List<Message> in = convo(2, 100);
        ContextManager mgr = manager(policy(100_000, 0.8, 0.5), ContextCompactors.dropOldest(), r);
        assertThat(mgr.prepare(in, 0).changed()).isFalse();
        assertThat(r.compactions).isEmpty();
        assertThat(r.usages.get(0).window().tokens()).isEqualTo(100_000);
    }

    @Test
    void theTriggerIsStrictlyAbove() {
        List<Message> in = convo(10, 400);
        long used = size(in);
        // a window whose trigger lands exactly on the size: no compaction; one token less: compaction
        ContextPolicy exact = ContextPolicy.builder().window(ContextWindow.of((int) used)).triggerAt(1.0).targetAt(0.5).keepRecent(2).build();
        assertThat(manager(exact, ContextCompactors.dropOldest(), new Recorder()).prepare(in, 0).changed()).isFalse();
        ContextPolicy tighter = ContextPolicy.builder().window(ContextWindow.of((int) used - 1)).triggerAt(1.0).targetAt(0.5).keepRecent(2).build();
        assertThat(manager(tighter, ContextCompactors.dropOldest(), new Recorder()).prepare(in, 0).changed()).isTrue();
    }

    @Test
    void aboveTheTriggerItCompactsTowardsTheTarget() {
        Recorder r = new Recorder();
        List<Message> in = convo(30, 400);
        long used = size(in);
        ContextPolicy p = policy((int) (used * 1.1), 0.8, 0.5);
        ContextManager mgr = manager(p, ContextCompactors.dropOldest(), r);
        ContextManager.Prepared prepared = mgr.prepare(in, 3);

        assertThat(prepared.changed()).isTrue();
        assertThat(size(prepared.messages())).isLessThanOrEqualTo(p.targetTokens().orElseThrow());
        assertThat(r.compactions).hasSize(1);
        CompactionEvent e = r.compactions.get(0);
        assertThat(e.reason()).isEqualTo(CompactionRequest.Reason.THRESHOLD);
        assertThat(e.iteration()).isEqualTo(3);
        assertThat(e.result().outcome()).isEqualTo(CompactionResult.Outcome.COMPACTED);
        assertThat(e.before().used()).isEqualTo(used);
        assertThat(e.after().used()).isEqualTo(e.result().tokensAfter());
        assertThat(e.failure()).isNull();
        assertThat(prepared.usage().used()).isEqualTo(used); // the usage reported is what was measured before compaction
    }

    @Test
    void reportedTokensPlusWhatWasAddedSinceBeatAFullEstimate() {
        Recorder r = new Recorder();
        List<Message> in = convo(10, 400);
        ContextManager mgr = manager(policy(1_000_000, 0.8, 0.5), null, r);
        mgr.reported(9_000, 6);
        mgr.prepare(in, 1);
        ContextUsage u = r.usages.get(0);
        assertThat(u.source()).isEqualTo(ContextUsage.Source.REPORTED);
        assertThat(u.used()).isEqualTo(9_000 + size(in.subList(6, in.size())));
    }

    @Test
    void noReportMeansAnEstimateOfEverythingPlusTheFixedOverhead() {
        Recorder r = new Recorder();
        List<Message> in = convo(3, 400);
        ContextManager mgr = new ContextManager(policy(1_000_000, 0.8, 0.5), null, CharsPerTokenEstimator.INSTANCE, 250, r);
        mgr.prepare(in, 0);
        assertThat(r.usages.get(0).used()).isEqualTo(250 + size(in));
        assertThat(r.usages.get(0).source()).isEqualTo(ContextUsage.Source.ESTIMATED);
    }

    @Test
    void theOverheadOfToolDefinitionsComesOffTheTarget() {
        Recorder r = new Recorder();
        List<Message> in = convo(30, 400);
        ContextPolicy p = policy((int) (size(in) * 1.1), 0.8, 0.5);
        ContextManager mgr = new ContextManager(p, ContextCompactors.dropOldest(), CharsPerTokenEstimator.INSTANCE, 1_000, r);
        ContextManager.Prepared prepared = mgr.prepare(in, 0);
        assertThat(size(prepared.messages()) + 1_000).isLessThanOrEqualTo(p.targetTokens().orElseThrow());
    }

    @Test
    void afterACompactionTheOldReportIsNotUsed() {
        Recorder r = new Recorder();
        List<Message> in = convo(30, 400);
        ContextManager mgr = manager(policy((int) (size(in) * 1.1), 0.8, 0.5), ContextCompactors.dropOldest(), r);
        mgr.reported((int) size(in), in.size());
        ContextManager.Prepared first = mgr.prepare(in, 0);
        assertThat(first.changed()).isTrue();
        mgr.prepare(first.messages(), 1);
        assertThat(r.usages.get(1).source()).isEqualTo(ContextUsage.Source.ESTIMATED);
        assertThat(r.usages.get(1).used()).isEqualTo(size(first.messages()));
    }

    // ── the guards ─────────────────────────────────────────────────────────────────

    @Test
    void aResultThatGainsTooLittleIsDiscarded() {
        Recorder r = new Recorder();
        List<Message> in = convo(30, 400);
        // removes exactly one message: far less than the 10 % the policy asks for
        ContextCompactor shy = (m, q) -> CompactionResult.of("shy", m, m.subList(0, m.size() - 1), q);
        ContextManager mgr = manager(policy((int) (size(in) * 1.1), 0.8, 0.5), shy, r);
        ContextManager.Prepared p = mgr.prepare(in, 0);
        assertThat(p.changed()).isFalse();
        assertThat(p.messages()).isEqualTo(in);
        assertThat(r.compactions.get(0).result().outcome()).isEqualTo(CompactionResult.Outcome.NO_GAIN);
        assertThat(r.compactions.get(0).result().note()).contains("minGain");
    }

    @Test
    void afterTwoNoGainsInARowItStopsAndSaysSoOnce() {
        Recorder r = new Recorder();
        List<Message> in = convo(30, 400);
        AtomicInteger called = new AtomicInteger();
        ContextCompactor nothing = (m, q) -> {
            called.incrementAndGet();
            return CompactionResult.of("nothing", m, m, q);
        };
        ContextManager mgr = manager(policy((int) (size(in) * 1.1), 0.8, 0.5), nothing, r);
        for (int i = 0; i < 6; i++) mgr.prepare(in, i);
        assertThat(called).hasValue(2);
        assertThat(r.compactions).hasSize(3);
        assertThat(r.compactions.get(0).result().outcome()).isEqualTo(CompactionResult.Outcome.NO_GAIN);
        assertThat(r.compactions.get(1).result().outcome()).isEqualTo(CompactionResult.Outcome.NO_GAIN);
        CompactionEvent stop = r.compactions.get(2);
        assertThat(stop.result().outcome()).isEqualTo(CompactionResult.Outcome.SKIPPED);
        assertThat(stop.result().note()).contains("stopped").contains("no gain");
        assertThat(r.usages).hasSize(6); // usage keeps being reported
    }

    @Test
    void aSuccessResetsTheNoGainCount() {
        Recorder r = new Recorder();
        AtomicInteger n = new AtomicInteger();
        ContextCompactor flaky = (m, q) -> n.incrementAndGet() == 2
                ? ContextCompactors.dropOldest().compact(m, q)
                : CompactionResult.of("flaky", m, m, q);
        List<Message> in = convo(60, 400);
        ContextManager mgr = manager(policy((int) (size(in) * 1.1), 0.8, 0.5), flaky, r);
        mgr.prepare(in, 0); // no gain
        ContextManager.Prepared second = mgr.prepare(in, 1); // gain
        assertThat(second.changed()).isTrue();
        mgr.prepare(in, 2); // no gain again, still allowed to try
        assertThat(n).hasValue(3);
    }

    @Test
    void afterTheMaximumNumberOfCompactionsItStops() {
        Recorder r = new Recorder();
        ContextPolicy p = ContextPolicy.builder().window(ContextWindow.of(4_000)).triggerAt(0.8).targetAt(0.5).keepRecent(2).maxCompactions(2).build();
        ContextManager mgr = manager(p, ContextCompactors.dropOldest(), r);
        List<Message> grown = convo(40, 400);
        for (int i = 0; i < 4; i++) mgr.prepare(grown, i);
        long compacted = r.compactions.stream().filter(e -> e.result().outcome() == CompactionResult.Outcome.COMPACTED).count();
        assertThat(compacted).isEqualTo(2);
        CompactionEvent stop = r.compactions.get(r.compactions.size() - 1);
        assertThat(stop.result().outcome()).isEqualTo(CompactionResult.Outcome.SKIPPED);
        assertThat(stop.result().note()).contains("stopped").contains("2 compactions");
        assertThat(r.compactions.stream().filter(e -> e.result().outcome() == CompactionResult.Outcome.SKIPPED)).hasSize(1);
    }

    @Test
    void aCompactorThatThrowsDoesNotFailTheRun() {
        Recorder r = new Recorder();
        List<Message> in = convo(30, 400);
        ContextCompactor boom = (m, q) -> {
            throw new IllegalStateException("bang");
        };
        ContextManager mgr = manager(policy((int) (size(in) * 1.1), 0.8, 0.5), boom, r);
        ContextManager.Prepared p = mgr.prepare(in, 0);
        assertThat(p.changed()).isFalse();
        assertThat(p.messages()).isEqualTo(in);
        CompactionEvent e = r.compactions.get(0);
        assertThat(e.failure()).contains("IllegalStateException").contains("bang");
        assertThat(e.result().outcome()).isEqualTo(CompactionResult.Outcome.SKIPPED);
        assertThat(e.result().note()).contains("failed");
    }

    @Test
    void aCompactorThatKeepsFailingIsGivenUpOn() {
        Recorder r = new Recorder();
        AtomicInteger called = new AtomicInteger();
        ContextCompactor boom = (m, q) -> {
            called.incrementAndGet();
            throw new IllegalStateException("bang");
        };
        List<Message> in = convo(30, 400);
        ContextManager mgr = manager(policy((int) (size(in) * 1.1), 0.8, 0.5), boom, r);
        for (int i = 0; i < 5; i++) mgr.prepare(in, i);
        assertThat(called).hasValue(2);
    }

    @Test
    void aBiggerResultFromARawCompactorIsNotUsed() {
        Recorder r = new Recorder();
        List<Message> in = convo(30, 400);
        // builds the result by hand, bypassing the 'never bigger' help in CompactionResult.of
        ContextCompactor greedy = (m, q) -> {
            List<Message> bigger = new ArrayList<>(m);
            bigger.add(Message.user("x".repeat(50_000)));
            return new CompactionResult(bigger, "greedy", CompactionResult.Outcome.COMPACTED, m.size(), bigger.size(), 1, 1, 0, null, null);
        };
        ContextManager mgr = manager(policy((int) (size(in) * 1.1), 0.8, 0.5), greedy, r);
        ContextManager.Prepared p = mgr.prepare(in, 0);
        assertThat(p.changed()).isFalse();
        assertThat(r.compactions.get(0).result().outcome()).isEqualTo(CompactionResult.Outcome.NO_GAIN);
    }

    @Test
    void twoManagersShareNothing() {
        List<Message> in = convo(30, 400);
        ContextPolicy p = policy((int) (size(in) * 1.1), 0.8, 0.5);
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        ContextManager m1 = manager(p, ContextCompactors.dropOldest(), a);
        ContextManager m2 = manager(p, ContextCompactors.dropOldest(), b);
        m1.prepare(in, 0);
        m2.prepare(in, 0);
        assertThat(a.compactions).hasSize(1);
        assertThat(b.compactions).hasSize(1);
    }

    @Test
    void aStrategyThatOverstatesItsSavingIsNotBelieved() {
        Recorder r = new Recorder();
        List<Message> in = convo(30, 400);
        // really removes one message (about 1 %), but claims to have halved the prompt
        ContextCompactor boaster = (m, q) -> {
            List<Message> out = m.subList(0, m.size() - 1);
            return new CompactionResult(out, "boaster", CompactionResult.Outcome.COMPACTED, m.size(), out.size(), 1_000, 500, 1, null, null);
        };
        ContextManager mgr = manager(policy((int) (size(in) * 1.1), 0.8, 0.5), boaster, r);
        assertThat(mgr.prepare(in, 0).changed()).isFalse();
        assertThat(r.compactions.get(0).result().outcome()).isEqualTo(CompactionResult.Outcome.NO_GAIN);
    }

    @Test
    void theOverheadCanBeGivenForEachCall() {
        Recorder r = new Recorder();
        List<Message> in = convo(3, 400);
        ContextManager mgr = new ContextManager(policy(1_000_000, 0.8, 0.5), null, CharsPerTokenEstimator.INSTANCE, 100, r);
        mgr.prepare(in, 0, 700); // this call carries 700 tokens besides its messages, not the 100 the manager was built with
        mgr.prepare(in, 1);      // the next one falls back to the built-in 100
        assertThat(r.usages.get(0).used()).isEqualTo(700 + size(in));
        assertThat(r.usages.get(1).used()).isEqualTo(100 + size(in));
    }

    @Test
    void aGivenOverheadComesOffTheTargetToo() {
        Recorder r = new Recorder();
        List<Message> in = convo(30, 400);
        ContextPolicy p = policy((int) (size(in) * 1.1), 0.8, 0.5);
        ContextManager mgr = manager(p, ContextCompactors.dropOldest(), r);
        ContextManager.Prepared prepared = mgr.prepare(in, 0, 1_500);
        assertThat(size(prepared.messages()) + 1_500).isLessThanOrEqualTo(p.targetTokens().orElseThrow());
    }

    // ── a prompt that cannot fit (R16) ─────────────────────────────────────────────

    @Test
    void aPromptFarOverTheWindowFailsBeforeAnyCallEvenWithNoCompactor() {
        Recorder r = new Recorder();
        List<Message> in = convo(30, 2_000);
        ContextManager mgr = manager(policy(1_000, 0.8, 0.5), null, r);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mgr.prepare(in, 0))
                .isInstanceOf(io.github.llm4j.exception.ContextOverflowException.class)
                .hasMessageContaining("1000").hasMessageContaining("cannot fit");
        assertThat(r.usages).hasSize(1); // the usage was still reported
    }

    @Test
    void aPromptThatCompactionBringsUnderTheWindowIsFine() {
        Recorder r = new Recorder();
        List<Message> in = convo(30, 400);
        ContextManager mgr = manager(policy((int) (size(in) * 0.6), 0.8, 0.5), ContextCompactors.dropOldest(), r);
        assertThat(mgr.prepare(in, 0).changed()).isTrue();
    }

    @Test
    void aPromptThatEvenCompactionCannotBringUnderTheWindowFails() {
        Recorder r = new Recorder();
        // the protected content alone is bigger than the window
        List<Message> in = new Convo().system("s").task("t".repeat(40_000)).step("a", 300).step("b", 300).step("c", 300).build();
        ContextManager mgr = manager(policy(2_000, 0.8, 0.5), ContextCompactors.dropOldest(), r);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mgr.prepare(in, 0)).isInstanceOf(io.github.llm4j.exception.ContextOverflowException.class);
        assertThat(r.compactions).isNotEmpty(); // it tried first
    }

    @Test
    void anEstimateALittleOverTheWindowIsNotTreatedAsCertain() {
        // estimates are rough, so only a prompt well over the window (25 %) is refused before the provider is asked
        Recorder r = new Recorder();
        List<Message> in = convo(10, 400);
        ContextManager mgr = manager(policy((int) (size(in) / 1.2), 0.8, 0.5), null, r);
        assertThat(mgr.prepare(in, 0).changed()).isFalse(); // 20 % over: sent, and the provider decides
    }

    // ── a provider rejected the prompt (R15) ───────────────────────────────────────

    @Test
    void afterAnOverflowItCompactsHarderThanTheThresholdWould() {
        Recorder r = new Recorder();
        List<Message> in = convo(30, 400);
        ContextPolicy p = policy((int) (size(in) * 1.1), 0.8, 0.5);
        ContextManager mgr = manager(p, ContextCompactors.dropOldest(), r);
        ContextManager.Prepared prepared = mgr.recoverFromOverflow(in, 4, 0);
        assertThat(prepared.changed()).isTrue();
        assertThat(size(prepared.messages())).isLessThanOrEqualTo(p.targetTokens().orElseThrow() / 2);
        assertThat(r.compactions).hasSize(1);
        assertThat(r.compactions.get(0).reason()).isEqualTo(CompactionRequest.Reason.OVERFLOW);
        assertThat(r.compactions.get(0).iteration()).isEqualTo(4);
        assertThat(r.usages).isEmpty(); // not a new measurement of a prompt about to be sent
    }

    @Test
    void anOverflowCanBeRecoveredFromEvenWhenTheWindowIsNotKnown() {
        Recorder r = new Recorder();
        List<Message> in = convo(30, 400);
        ContextManager mgr = manager(ContextPolicy.builder().keepRecent(2).build(), ContextCompactors.dropOldest(), r);
        ContextManager.Prepared prepared = mgr.recoverFromOverflow(in, 0, 0);
        assertThat(prepared.changed()).isTrue();
        assertThat(size(prepared.messages())).isLessThanOrEqualTo(size(in) / 2);
    }

    @Test
    void withoutACompactorOrWhenToldToFailNothingIsDone() {
        List<Message> in = convo(30, 400);
        assertThat(manager(ContextPolicy.builder().build(), null, new Recorder()).recoverFromOverflow(in, 0, 0).changed()).isFalse();
        ContextPolicy fail = ContextPolicy.builder().onOverflow(ContextPolicy.OnOverflow.FAIL).keepRecent(2).build();
        Recorder r = new Recorder();
        assertThat(manager(fail, ContextCompactors.dropOldest(), r).recoverFromOverflow(in, 0, 0).changed()).isFalse();
        assertThat(r.compactions).isEmpty();
    }

    @Test
    void whenItCannotShrinkTheConversationItSaysSo() {
        Recorder r = new Recorder();
        List<Message> in = new Convo().system("s").task("task").build();
        ContextManager.Prepared p = manager(ContextPolicy.builder().build(), ContextCompactors.dropOldest(), r).recoverFromOverflow(in, 0, 0);
        assertThat(p.changed()).isFalse();
    }

    @Test
    void theSameGuardsApplyToRecovery() {
        Recorder r = new Recorder();
        List<Message> in = convo(30, 400);
        ContextCompactor nothing = (m, q) -> CompactionResult.of("nothing", m, m, q);
        ContextManager mgr = manager(ContextPolicy.builder().keepRecent(2).build(), nothing, r);
        assertThat(mgr.recoverFromOverflow(in, 0, 0).changed()).isFalse();
        assertThat(mgr.recoverFromOverflow(in, 1, 0).changed()).isFalse();
        assertThat(mgr.recoverFromOverflow(in, 2, 0).changed()).isFalse();
        assertThat(r.compactions).hasSize(3);
        assertThat(r.compactions.get(2).result().note()).contains("stopped");
    }
}
