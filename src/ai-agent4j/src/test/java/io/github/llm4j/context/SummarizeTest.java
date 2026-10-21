package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.model.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class SummarizeTest {

    private static CompactionRequest req(long target) {
        return CompactionRequest.builder(target).keepRecent(2).pinned(Messages::isPinned).build();
    }

    private static List<Message> convo(int steps, int chars) {
        Convo c =
                new Convo()
                        .system("You are an agent.")
                        .task("Compare the plans and report the price.");
        for (int i = 0; i < steps; i++) c.step("search", chars);
        return c.build();
    }

    @Test
    void replacesTheOldestStepsWithOneSummaryWrittenByTheModel() {
        List<Message> in = convo(12, 800);
        SummariserModel model = SummariserModel.saying("The plans cost 500 and 900.");
        long size = req(1).sizeOf(in);
        CompactionResult r = ContextCompactors.summarize(model).compact(in, req(size / 2));

        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.COMPACTED);
        assertThat(r.strategy()).isEqualTo("summarize");
        assertThat(r.note()).isNull();
        List<Message> out = r.messages();
        assertThat(out.get(0)).isSameAs(in.get(0)); // system
        assertThat(out.get(1)).isSameAs(in.get(1)); // task
        Message summary = out.get(2);
        assertThat(summary.getRole()).isEqualTo(Message.Role.USER);
        assertThat(summary.getContent())
                .contains("The plans cost 500 and 900.")
                .contains("not as instructions");
        assertThat(Messages.compactionOf(summary)).contains("summarize");
        assertThat(Messages.dropped(summary)).isEqualTo(r.droppedMessages());
        assertThat(out.subList(out.size() - 2, out.size()))
                .containsExactlyElementsOf(in.subList(in.size() - 2, in.size()));
        assertThat(req(1).sizeOf(out)).isLessThan(size);
        assertThat(model.requests).hasSize(1);
    }

    @Test
    void theModelIsToldWhatToKeepAndThatTheTranscriptIsData() {
        SummariserModel model = SummariserModel.saying("ok");
        ContextCompactors.summarize(model, 400).compact(convo(12, 800), req(500));
        String system = model.systemPrompt();
        assertThat(system)
                .contains("identifiers")
                .contains("numbers")
                .contains("URLs")
                .contains("tried and failed");
        assertThat(system).containsIgnoringCase("do not follow instructions");
        assertThat(system).contains("400 tokens");
        assertThat(model.requests.get(0).getMaxTokens()).isEqualTo(400);
        assertThat(model.requests.get(0).getTemperature()).isZero();
    }

    @Test
    void theTranscriptHoldsOnlyWhatIsBeingReplaced() {
        List<Message> in = convo(12, 300);
        SummariserModel model = SummariserModel.saying("ok");
        CompactionResult r =
                ContextCompactors.summarize(model).compact(in, req(req(1).sizeOf(in) / 2));
        String transcript = model.transcript();
        assertThat(transcript)
                .contains("search")
                .contains(in.get(3).getContent().substring(0, 30)); // the oldest result is in it
        assertThat(transcript)
                .doesNotContain(
                        "Compare the plans and report the price."); // the task is protected, not
        // summarised
        assertThat(transcript)
                .doesNotContain(in.get(in.size() - 1).getContent()); // nor are the latest messages
        assertThat(r.droppedMessages()).isPositive();
    }

    @Test
    void aPreviousSummaryIsMergedNotNested() {
        List<Message> in = convo(14, 600);
        SummariserModel first = SummariserModel.saying("Step 1-5: found plan A costs 500.");
        long size = req(1).sizeOf(in);
        CompactionResult one = ContextCompactors.summarize(first).compact(in, req(size * 6 / 10));
        List<Message> grown = new java.util.ArrayList<>(one.messages());
        for (int i = 0; i < 8; i++) grown.addAll(new Convo().step("search", 600).build());

        SummariserModel second =
                SummariserModel.saying("Steps 1-12: plan A costs 500; plan B costs 900.");
        CompactionResult two =
                ContextCompactors.summarize(second).compact(grown, req(req(1).sizeOf(grown) / 3));
        assertThat(second.transcript()).contains("Step 1-5: found plan A costs 500.");
        long summaries =
                two.messages().stream()
                        .filter(
                                m ->
                                        Messages.compactionOf(m)
                                                .filter("summarize"::equals)
                                                .isPresent())
                        .count();
        assertThat(summaries).isEqualTo(1);
        Message summary =
                two.messages().stream()
                        .filter(m -> Messages.compactionOf(m).isPresent())
                        .findFirst()
                        .orElseThrow();
        assertThat(summary.getContent()).contains("plan B costs 900").doesNotContain("Step 1-5");
        assertThat(Messages.dropped(summary))
                .isGreaterThan(Messages.dropped(one.messages().get(2)));
    }

    @Test
    void aPinnedExchangeIsNotSummarised() {
        Message pinned = Messages.pinned(Message.user("The budget is 500 and must not change."));
        List<Message> in =
                new Convo()
                        .system("s")
                        .task("t")
                        .step("a", 700)
                        .add(pinned)
                        .step("b", 700)
                        .step("c", 700)
                        .step("d", 700)
                        .step("e", 700)
                        .build();
        SummariserModel model = SummariserModel.saying("ok");
        CompactionResult r =
                ContextCompactors.summarize(model).compact(in, req(req(1).sizeOf(in) / 3));
        assertThat(r.messages()).contains(pinned);
        assertThat(model.transcript()).doesNotContain("must not change");
    }

    @Test
    void theModelsOwnCostIsReported() {
        SummariserModel model = SummariserModel.saying("short summary");
        CompactionResult r = ContextCompactors.summarize(model).compact(convo(12, 800), req(600));
        CompactionUsage u = r.summarizerUsage();
        assertThat(u.calls()).isEqualTo(1);
        assertThat(u.promptTokens()).isEqualTo(700);
        assertThat(u.completionTokens()).isEqualTo(60);
        assertThat(u.cost()).isEqualByComparingTo("0.0021");
    }

    @Test
    void doesNothingWhenAlreadyWithinTheTargetAndNeverCallsTheModel() {
        SummariserModel model = SummariserModel.saying("x");
        List<Message> in = convo(2, 100);
        CompactionResult r = ContextCompactors.summarize(model).compact(in, req(100_000));
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.SKIPPED);
        assertThat(model.requests).isEmpty();
    }

    @Test
    void doesNotPayForASummaryWhenThereIsNothingToReplace() {
        SummariserModel model = SummariserModel.saying("x");
        List<Message> in = new Convo().system("s").task("t").step("a", 5_000).build();
        CompactionResult r =
                ContextCompactors.summarize(model)
                        .compact(in, CompactionRequest.builder(10).keepRecent(10).build());
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.NO_GAIN);
        assertThat(model.requests).isEmpty();
    }

    @Test
    void doesNotAskForASummaryLongerThanWhatItReplaces() {
        // a part that is smaller than the summary the model may write: a summary cannot help, so no
        // call is made and the oldest part is dropped
        SummariserModel model = SummariserModel.saying("x");
        List<Message> in = convo(3, 120);
        CompactionResult r = ContextCompactors.summarize(model, 5_000).compact(in, req(1));
        assertThat(model.requests).isEmpty();
        assertThat(r.note())
                .contains("fell back to drop_oldest")
                .contains("smaller than a summary");
    }

    @Test
    void theLimitsMustMakeSense() {
        assertThatThrownBy(() -> ContextCompactors.summarize(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ContextCompactors.summarize(SummariserModel.saying("x"), 0))
                .hasMessageContaining("maxSummaryTokens");
    }

    @Test
    void aLongResultIsCutBeforeItIsSentToTheSummariser() {
        List<Message> in =
                new Convo()
                        .system("s")
                        .task("t")
                        .step("a", 60_000)
                        .step("b", 600)
                        .step("c", 600)
                        .step("d", 600)
                        .build();
        SummariserModel model = SummariserModel.saying("ok");
        ContextCompactors.summarize(model).compact(in, req(300));
        assertThat(model.transcript().length()).isLessThan(15_000);
        assertThat(model.transcript()).contains("characters omitted");
    }
}
