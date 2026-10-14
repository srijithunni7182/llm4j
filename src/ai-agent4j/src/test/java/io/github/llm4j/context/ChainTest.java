package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.model.Message;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChainTest {

    private static CompactionRequest req(long target) {
        return CompactionRequest.builder(target).keepRecent(2).build();
    }

    private static List<Message> convo() {
        Convo c = new Convo().system("sys").task("task");
        for (int i = 0; i < 12; i++) c.step("fetch", 4_000);
        return c.build();
    }

    @Test
    void stopsAtTheFirstStrategyThatReachesTheTarget() {
        List<String> called = new ArrayList<>();
        ContextCompactor trim = ContextCompactors.trimToolResults(500);
        ContextCompactor spy = (m, r) -> {
            called.add("spy");
            return CompactionResult.of("spy", m, m, r);
        };
        List<Message> in = convo();
        long target = req(1).sizeOf(in) / 2;
        CompactionResult r = ContextCompactors.chain(trim, spy).compact(in, req(target));
        assertThat(called).isEmpty();
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.COMPACTED);
        assertThat(req(1).sizeOf(r.messages())).isLessThanOrEqualTo(target);
    }

    @Test
    void goesOnToTheNextStrategyWhenTheFirstIsNotEnough() {
        List<Message> in = convo();
        long target = 600; // trimming alone cannot reach this
        CompactionResult r = ContextCompactors.chain(ContextCompactors.trimToolResults(500), ContextCompactors.dropOldest()).compact(in, req(target));
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.COMPACTED);
        assertThat(r.messages().stream().anyMatch(m -> Messages.compactionOf(m).filter("drop_oldest"::equals).isPresent())).isTrue();
        assertThat(r.strategy()).isEqualTo("chain(trim_tool_results, drop_oldest)");
    }

    @Test
    void reportsTheWholeChainAgainstTheOriginalInput() {
        List<Message> in = convo();
        CompactionRequest req = req(600);
        CompactionResult r = ContextCompactors.chain(ContextCompactors.trimToolResults(500), ContextCompactors.dropOldest()).compact(in, req);
        assertThat(r.messagesBefore()).isEqualTo(in.size());
        assertThat(r.tokensBefore()).isEqualTo(req.sizeOf(in));
        assertThat(r.tokensAfter()).isEqualTo(req.sizeOf(r.messages()));
        assertThat(r.droppedMessages()).isPositive();
    }

    @Test
    void anAlreadySmallConversationIsSkipped() {
        List<Message> in = new Convo().system("s").task("t").answer("a").build();
        CompactionResult r = ContextCompactors.chain(ContextCompactors.dropOldest()).compact(in, req(10_000));
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.SKIPPED);
    }

    @Test
    void needsAtLeastOneStrategyAndNoNulls() {
        assertThatThrownBy(() -> ContextCompactors.chain()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContextCompactors.chain((ContextCompactor) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aStrategyThatThrowsPropagatesSoTheManagerCanHandleIt() {
        ContextCompactor boom = (m, r) -> {
            throw new IllegalStateException("boom");
        };
        assertThatThrownBy(() -> ContextCompactors.chain(boom).compact(convo(), req(10))).hasMessage("boom");
    }

    @Test
    void whatTheStagesSpentOnModelCallsIsAddedUp() {
        CompactionUsage one = new CompactionUsage(1, 700, 60, new java.math.BigDecimal("0.002"));
        ContextCompactor spenderA = (m, r) -> ContextCompactors.dropOldest().compact(m, CompactionRequest.builder(r.targetTokens() * 2).keepRecent(2).build()).withUsage(one);
        ContextCompactor spenderB = (m, r) -> ContextCompactors.dropOldest().compact(m, r).withUsage(new CompactionUsage(2, 100, 20, null));
        List<Message> in = convo();
        CompactionResult r = ContextCompactors.chain(spenderA, spenderB).compact(in, req(300));
        assertThat(r.summarizerUsage().calls()).isEqualTo(3);
        assertThat(r.summarizerUsage().promptTokens()).isEqualTo(800);
        assertThat(r.summarizerUsage().completionTokens()).isEqualTo(80);
        assertThat(r.summarizerUsage().cost()).isEqualByComparingTo("0.002");
    }

    @Test
    void usageIsKeptEvenWhenTheChainGainsNothing() {
        CompactionUsage one = new CompactionUsage(1, 10, 5, null);
        ContextCompactor wasteful = (m, r) -> CompactionResult.of("w", m, m, r).withUsage(one);
        CompactionResult r = ContextCompactors.chain(wasteful).compact(convo(), req(300));
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.NO_GAIN);
        assertThat(r.summarizerUsage()).isEqualTo(one);
    }

    @Test
    void noModelCallsMeansNoUsage() {
        assertThat(ContextCompactors.chain(ContextCompactors.dropOldest()).compact(convo(), req(300)).summarizerUsage()).isNull();
    }
}
