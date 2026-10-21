package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.budget.Budget;
import io.github.llm4j.budget.BudgetedLLMClient;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A summary is a nicety: whatever goes wrong with it, the conversation is still made smaller, by
 * dropping the oldest steps (R12).
 */
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
        assertThat(
                        r.messages().stream()
                                .anyMatch(
                                        m ->
                                                Messages.compactionOf(m)
                                                        .filter("drop_oldest"::equals)
                                                        .isPresent()))
                .isTrue();
        assertThat(req(1).sizeOf(r.messages())).isLessThan(req(1).sizeOf(in));
    }

    @Test
    void aFailingModelCall() {
        List<Message> in = convo();
        CompactionResult r =
                ContextCompactors.summarize(
                                SummariserModel.failing(
                                        new ProviderException("test", "503 from the provider")))
                        .compact(in, req(req(1).sizeOf(in) / 2));
        assertFellBack(r, "503 from the provider", in);
        assertThat(r.summarizerUsage()).isNull(); // nothing came back, so nothing is counted
    }

    @Test
    void anyOtherFailureToo() {
        List<Message> in = convo();
        CompactionResult r =
                ContextCompactors.summarize(
                                SummariserModel.failing(new IllegalStateException("socket closed")))
                        .compact(in, req(req(1).sizeOf(in) / 2));
        assertFellBack(r, "socket closed", in);
    }

    @Test
    void anEmptySummaryStillCostsWhatItCost() {
        List<Message> in = convo();
        CompactionResult r =
                ContextCompactors.summarize(SummariserModel.saying("   \n "))
                        .compact(in, req(req(1).sizeOf(in) / 2));
        assertFellBack(r, "empty", in);
        assertThat(r.summarizerUsage().calls()).isEqualTo(1);
    }

    @Test
    void aSummaryWithNoContentAtAll() {
        List<Message> in = convo();
        SummariserModel nothing =
                new SummariserModel(
                        q ->
                                LLMResponse.builder()
                                        .content(null)
                                        .model("m")
                                        .tokenUsage(5, 0, 5)
                                        .build());
        assertFellBack(
                ContextCompactors.summarize(nothing).compact(in, req(req(1).sizeOf(in) / 2)),
                "empty",
                in);
    }

    @Test
    void aSummaryLongerThanWhatItReplaces() {
        List<Message> in = convo();
        CompactionResult r =
                ContextCompactors.summarize(SummariserModel.saying("blah ".repeat(20_000)))
                        .compact(in, req(req(1).sizeOf(in) / 2));
        assertFellBack(r, "not smaller", in);
        assertThat(r.summarizerUsage().calls()).isEqualTo(1);
    }

    @Test
    void aBudgetThatRefusesTheCall() {
        List<Message> in = convo();
        Budget budget = Budget.builder().tokens(10).build();
        BudgetedLLMClient metered =
                BudgetedLLMClient.builder(SummariserModel.saying("never reached"))
                        .budget(budget)
                        .build();
        CompactionResult r =
                ContextCompactors.summarize(metered).compact(in, req(req(1).sizeOf(in) / 2));
        assertThat(r.note()).containsIgnoringCase("budget");
        assertFellBack(r, "fell back", in);
    }

    @Test
    void aRateLimitDoesNotPauseTheRun() {
        List<Message> in = convo();
        io.github.llm4j.ratelimit.RateLimited limited =
                new io.github.llm4j.ratelimit.RateLimited(
                        new io.github.llm4j.exception.RateLimitException("slow down")
                                .infoOrEstimate(
                                        java.time.Instant.now(), java.time.Duration.ofSeconds(60)),
                        io.github.llm4j.ratelimit.RateLimited.Reason.PROVIDER_LIMIT);
        CompactionResult r =
                ContextCompactors.summarize(SummariserModel.failing(limited))
                        .compact(in, req(req(1).sizeOf(in) / 2));
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.COMPACTED);
        assertThat(r.note()).contains("fell back");
    }

    // ── a fallback never throws away an earlier summary (found by the generated contract cases) ──

    private static List<Message> withAnEarlierSummary() {
        List<Message> first =
                new Convo()
                        .system("sys")
                        .task("task")
                        .step("a", 800)
                        .step("b", 800)
                        .step("c", 800)
                        .step("d", 800)
                        .step("e", 800)
                        .build();
        CompactionResult one =
                ContextCompactors.summarize(SummariserModel.saying("Plan A costs 500."))
                        .compact(first, req(req(1).sizeOf(first) / 2));
        List<Message> grown = new java.util.ArrayList<>(one.messages());
        for (int i = 0; i < 6; i++) grown.addAll(new Convo().step("search", 800).build());
        return grown;
    }

    private static boolean hasSummary(List<Message> messages) {
        return messages.stream()
                .anyMatch(
                        m ->
                                Messages.compactionOf(m).filter("summarize"::equals).isPresent()
                                        && m.getContent().contains("Plan A costs 500."));
    }

    @Test
    void whenTheCallFailsTheEarlierNotesAreKept() {
        List<Message> in = withAnEarlierSummary();
        CompactionResult r =
                ContextCompactors.summarize(
                                SummariserModel.failing(new IllegalStateException("down")))
                        .compact(in, req(req(1).sizeOf(in) / 2));
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.COMPACTED);
        assertThat(r.note()).contains("fell back");
        assertThat(hasSummary(r.messages()))
                .as("the notes written earlier survive the failed attempt")
                .isTrue();
        assertThat(req(1).sizeOf(r.messages())).isLessThan(req(1).sizeOf(in));
    }

    @Test
    void whenThereIsNothingNewToSummariseTheEarlierNotesAreLeftAlone() {
        List<Message> first =
                new Convo()
                        .system("sys")
                        .task("task")
                        .step("a", 800)
                        .step("b", 800)
                        .step("c", 800)
                        .build();
        SummariserModel model = SummariserModel.saying("Plan A costs 500.");
        CompactionRequest unreachable = CompactionRequest.builder(10).keepRecent(0).build();
        CompactionResult one = ContextCompactors.summarize(model).compact(first, unreachable);
        CompactionResult two =
                ContextCompactors.summarize(model).compact(one.messages(), unreachable);
        assertThat(two.outcome())
                .isIn(CompactionResult.Outcome.NO_GAIN, CompactionResult.Outcome.SKIPPED);
        assertThat(hasSummary(two.messages())).isTrue();
    }

    @Test
    void aLaterSuccessfulSummaryStillMergesTheEarlierOne() {
        List<Message> in = withAnEarlierSummary();
        SummariserModel model = SummariserModel.saying("Plan A costs 500; plan B costs 900.");
        CompactionResult r =
                ContextCompactors.summarize(model).compact(in, req(req(1).sizeOf(in) / 3));
        assertThat(model.transcript()).contains("Plan A costs 500.");
        assertThat(
                        r.messages().stream()
                                .filter(
                                        m ->
                                                Messages.compactionOf(m)
                                                        .filter("summarize"::equals)
                                                        .isPresent()))
                .hasSize(1);
    }
}
