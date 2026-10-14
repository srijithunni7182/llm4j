package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.budget.Budget;
import io.github.llm4j.budget.BudgetedLLMClient;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A summary is a nicety: whatever goes wrong with it, the conversation is still made smaller, by dropping the oldest steps (R12). */
class SummarizeFallbackTest {

    private static CompactionRequest req(long target) {
        return CompactionRequest.builder(target).keepRecent(2).build();
    }

    private static List<Message> convo() {
        Convo c = new Convo().system("sys").task("task");
        for (int i = 0; i < 12; i++) c.step("search", 800);
        return c.build();
    }

    private static void assertFellBack(CompactionResult r, String why, List<Message> in) {
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.COMPACTED);
        assertThat(r.strategy()).isEqualTo("summarize");
        assertThat(r.note()).contains("fell back to drop_oldest").contains(why);
        assertThat(r.messages().stream().anyMatch(m -> Messages.compactionOf(m).filter("drop_oldest"::equals).isPresent())).isTrue();
        assertThat(req(1).sizeOf(r.messages())).isLessThan(req(1).sizeOf(in));
    }

    @Test
    void aFailingModelCall() {
        List<Message> in = convo();
        CompactionResult r = ContextCompactors.summarize(SummariserModel.failing(new ProviderException("test", "503 from the provider")))
                .compact(in, req(req(1).sizeOf(in) / 2));
        assertFellBack(r, "503 from the provider", in);
        assertThat(r.summarizerUsage()).isNull(); // nothing came back, so nothing is counted
    }

    @Test
    void anyOtherFailureToo() {
        List<Message> in = convo();
        CompactionResult r = ContextCompactors.summarize(SummariserModel.failing(new IllegalStateException("socket closed")))
                .compact(in, req(req(1).sizeOf(in) / 2));
        assertFellBack(r, "socket closed", in);
    }

    @Test
    void anEmptySummaryStillCostsWhatItCost() {
        List<Message> in = convo();
        CompactionResult r = ContextCompactors.summarize(SummariserModel.saying("   \n ")).compact(in, req(req(1).sizeOf(in) / 2));
        assertFellBack(r, "empty", in);
        assertThat(r.summarizerUsage().calls()).isEqualTo(1);
    }

    @Test
    void aSummaryWithNoContentAtAll() {
        List<Message> in = convo();
        SummariserModel nothing = new SummariserModel(q -> LLMResponse.builder().content(null).model("m").tokenUsage(5, 0, 5).build());
        assertFellBack(ContextCompactors.summarize(nothing).compact(in, req(req(1).sizeOf(in) / 2)), "empty", in);
    }

    @Test
    void aSummaryLongerThanWhatItReplaces() {
        List<Message> in = convo();
        CompactionResult r = ContextCompactors.summarize(SummariserModel.saying("blah ".repeat(20_000)))
                .compact(in, req(req(1).sizeOf(in) / 2));
        assertFellBack(r, "not smaller", in);
        assertThat(r.summarizerUsage().calls()).isEqualTo(1);
    }

    @Test
    void aBudgetThatRefusesTheCall() {
        List<Message> in = convo();
        Budget budget = Budget.builder().tokens(10).build();
        BudgetedLLMClient metered = BudgetedLLMClient.builder(SummariserModel.saying("never reached")).budget(budget).build();
        CompactionResult r = ContextCompactors.summarize(metered).compact(in, req(req(1).sizeOf(in) / 2));
        assertThat(r.note()).containsIgnoringCase("budget"); assertFellBack(r, "fell back", in);
    }

    @Test
    void aRateLimitDoesNotPauseTheRun() {
        List<Message> in = convo();
        io.github.llm4j.ratelimit.RateLimited limited = new io.github.llm4j.ratelimit.RateLimited(
                new io.github.llm4j.exception.RateLimitException("slow down").infoOrEstimate(java.time.Instant.now(), java.time.Duration.ofSeconds(60)),
                io.github.llm4j.ratelimit.RateLimited.Reason.PROVIDER_LIMIT);
        CompactionResult r = ContextCompactors.summarize(SummariserModel.failing(limited)).compact(in, req(req(1).sizeOf(in) / 2));
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.COMPACTED);
        assertThat(r.note()).contains("fell back");
    }
}
