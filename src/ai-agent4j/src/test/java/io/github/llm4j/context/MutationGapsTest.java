package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.budget.CharsPerTokenEstimator;
import io.github.llm4j.exception.ContextOverflowException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Edges that a mutation run (PIT) showed the other tests leave open: exact boundaries, labels,
 * counts and rarely taken paths.
 */
class MutationGapsTest {

    private static long size(List<Message> m) {
        return CompactionRequest.of(1).sizeOf(m);
    }

    private static List<Message> convo(int steps, int chars) {
        Convo c = new Convo().system("sys").task("task");
        for (int i = 0; i < steps; i++) c.step("search", chars);
        return c.build();
    }

    // ── discovery by name ──────────────────────────────────────────────────────────

    @Test
    void noneTakesNoOptions() {
        assertThatThrownBy(() -> ContextCompactors.byName("none", Map.of("x", "1")))
                .hasMessageContaining("takes no options");
    }

    @Test
    void aChainToleratesStrayCommasAndSpaces() {
        assertThat(
                        ContextCompactors.byName(
                                        "chain",
                                        Map.of("strategies", " drop_oldest,, trim_tool_results , "))
                                .name())
                .isEqualTo("chain(drop_oldest, trim_tool_results)");
    }

    @Test
    void aNearMissIsSuggestedAndAFarMissIsNot() {
        assertThatThrownBy(() -> ContextCompactors.byName("nne", Map.of()))
                .hasMessageContaining("did you mean 'none'");
        assertThatThrownBy(() -> ContextCompactors.byName("dorp_oldest", Map.of()))
                .hasMessageContaining("did you mean 'drop_oldest'"); // a swap is two edits
        assertThatThrownBy(() -> ContextCompactors.byName("trim_tool_result", Map.of()))
                .hasMessageContaining("did you mean 'trim_tool_results'");
        assertThatThrownBy(() -> ContextCompactors.byName("drop_old", Map.of()))
                .hasMessageContaining(
                        "did you mean 'drop_oldest'"); // three edits of an eleven-letter name
        assertThatThrownBy(() -> ContextCompactors.byName("drop_ol", Map.of()))
                .hasMessageNotContaining("did you mean"); // four is too far
        assertThatThrownBy(() -> ContextCompactors.byName("zzzzzzzzzzzz", Map.of()))
                .hasMessageNotContaining("did you mean");
        assertThatThrownBy(() -> ContextCompactors.byName("xyz", Map.of()))
                .hasMessageNotContaining("did you mean");
    }

    @Test
    void trimToolResultsAcceptsExactlyItsMinimum() {
        assertThat(ContextCompactors.trimToolResults(40).name()).isEqualTo("trim_tool_results");
    }

    // ── exact targets ──────────────────────────────────────────────────────────────

    @Test
    void aConversationExactlyAtTheTargetIsLeftAloneAndOneTokenOverIsNot() {
        List<Message> in = convo(10, 600);
        long exact = size(in);
        SummariserModel model = SummariserModel.saying("notes");
        for (ContextCompactor c :
                List.of(
                        ContextCompactors.dropOldest(),
                        ContextCompactors.trimToolResults(100),
                        ContextCompactors.summarize(model, 50),
                        ContextCompactors.chain(ContextCompactors.dropOldest()))) {
            assertThat(
                            c.compact(in, CompactionRequest.builder(exact).keepRecent(2).build())
                                    .outcome())
                    .as(c.name() + " at the target")
                    .isEqualTo(CompactionResult.Outcome.SKIPPED);
            assertThat(
                            c.compact(
                                            in,
                                            CompactionRequest.builder(exact - 1)
                                                    .keepRecent(2)
                                                    .build())
                                    .outcome())
                    .as(c.name() + " one over")
                    .isNotEqualTo(CompactionResult.Outcome.SKIPPED);
        }
        assertThat(model.requests.size()).isGreaterThan(0);
    }

    @Test
    void aChainStopsAsSoonAsAStageReachesTheTargetExactly() {
        List<Message> in = convo(10, 600);
        List<Message> reduced = in.subList(0, 8);
        AtomicInteger later = new AtomicInteger();
        ContextCompactor first = (m, r) -> CompactionResult.of("first", m, reduced, r);
        ContextCompactor second =
                (m, r) -> {
                    later.incrementAndGet();
                    return CompactionResult.of("second", m, m, r);
                };
        CompactionResult r =
                ContextCompactors.chain(first, second)
                        .compact(
                                in, CompactionRequest.builder(size(reduced)).keepRecent(2).build());
        assertThat(later).hasValue(0);
        assertThat(r.messages()).isEqualTo(reduced);
    }

    @Test
    void theRecentWindowEndsExactlyWhereAnExchangeBegins() {
        // sys, task, then three steps of two messages: keeping 2 keeps exactly the last step, not
        // the one before it
        List<Message> in =
                new Convo()
                        .system("sys")
                        .task("task")
                        .step("a", 900)
                        .step("b", 900)
                        .step("c", 900)
                        .build();
        CompactionResult r =
                ContextCompactors.dropOldest()
                        .compact(in, CompactionRequest.builder(1).keepRecent(2).build());
        assertThat(r.messages()).hasSize(2 + 1 + 2); // sys, task, the marker, the last step
        assertThat(r.messages().subList(3, 5)).containsExactlyElementsOf(in.subList(6, 8));
    }

    @Test
    void aConversationOfNothingButSystemMessagesIsHandled() {
        List<Message> in =
                List.of(
                        Message.system("a ".repeat(500)),
                        Message.system("b ".repeat(500)),
                        Message.system("c ".repeat(500)));
        for (ContextCompactor c :
                List.of(
                        ContextCompactors.dropOldest(),
                        ContextCompactors.trimToolResults(100),
                        ContextCompactors.summarize(SummariserModel.saying("x")))) {
            CompactionResult r = c.compact(in, CompactionRequest.of(1));
            assertThat(r.messages()).isEqualTo(in);
        }
    }

    // ── the manager ────────────────────────────────────────────────────────────────

    private static ContextPolicy policy(int window) {
        return ContextPolicy.builder()
                .window(ContextWindow.of(window))
                .triggerAt(0.8)
                .targetAt(0.5)
                .keepRecent(2)
                .build();
    }

    @Test
    void aManagerWithoutAnEstimatorUsesTheDefaultOne() {
        List<Message> in = convo(3, 400);
        ContextManagerTest.Recorder r = new ContextManagerTest.Recorder();
        new ContextManager(policy(1_000_000), null, null, 0, r).prepare(in, 0);
        assertThat(r.usages.get(0).used()).isEqualTo(size(in));
    }

    @Test
    void onlyAUsableReportIsTakenIntoAccount() {
        List<Message> in = convo(4, 400);
        ContextManagerTest.Recorder r = new ContextManagerTest.Recorder();
        ContextManager m =
                new ContextManager(policy(1_000_000), null, CharsPerTokenEstimator.INSTANCE, 0, r);
        m.reported(0, 3); // no tokens: ignored
        m.prepare(in, 0);
        m.reported(500, -1); // an impossible count of messages: ignored
        m.prepare(in, 1);
        assertThat(r.usages.get(0).source()).isEqualTo(ContextUsage.Source.ESTIMATED);
        assertThat(r.usages.get(1).source()).isEqualTo(ContextUsage.Source.ESTIMATED);
        m.reported(500, 0); // a report that covered no messages is still a report
        m.prepare(in, 2);
        assertThat(r.usages.get(2).source()).isEqualTo(ContextUsage.Source.REPORTED);
        assertThat(r.usages.get(2).used()).isEqualTo(500 + size(in));
        m.reported(700, in.size()); // covering everything: nothing added since
        m.prepare(in, 3);
        assertThat(r.usages.get(3).used()).isEqualTo(700);
        m.reported(700, in.size() + 1); // more messages than there are: cannot be this conversation
        m.prepare(in, 4);
        assertThat(r.usages.get(4).source()).isEqualTo(ContextUsage.Source.ESTIMATED);
    }

    @Test
    void afterAnOverflowTheOverheadComesOffTheTarget() {
        List<Message> in = convo(30, 400);
        ContextManagerTest.Recorder r = new ContextManagerTest.Recorder();
        ContextManager m =
                new ContextManager(
                        ContextPolicy.builder().keepRecent(2).build(),
                        ContextCompactors.dropOldest(),
                        CharsPerTokenEstimator.INSTANCE,
                        0,
                        r);
        long overhead = 600;
        ContextManager.Prepared p = m.recoverFromOverflow(in, 0, overhead);
        long whole = overhead + size(in);
        assertThat(p.changed()).isTrue();
        assertThat(overhead + size(p.messages())).isLessThanOrEqualTo(whole / 2 + 1);
    }

    @Test
    void thePromptIsRefusedOnlyBeyondTheMarginAndTheReasonSaysWhy() {
        // find a conversation whose size is a multiple of 5, so that a window can put it exactly 25
        // % over
        List<Message> in = null;
        for (int chars = 400; chars < 420; chars++) {
            List<Message> candidate = convo(6, chars);
            if (size(candidate) % 5 == 0) {
                in = candidate;
                break;
            }
        }
        assertThat(in).isNotNull();
        long used = size(in);
        int exact = (int) (used * 4 / 5);
        ContextManager atTheMargin =
                new ContextManager(policy(exact), null, CharsPerTokenEstimator.INSTANCE, 0, null);
        assertThat(atTheMargin.prepare(in, 0).changed()).isFalse(); // exactly 25 % over: sent
        List<Message> finalIn = in;
        ContextManager beyond =
                new ContextManager(
                        policy(exact - 1), null, CharsPerTokenEstimator.INSTANCE, 0, null);
        assertThatThrownBy(() -> beyond.prepare(finalIn, 0))
                .isInstanceOf(ContextOverflowException.class)
                .hasMessageContaining("no compaction strategy is set")
                .hasMessageNotContaining("even after compaction");
        ContextManager withStrategy =
                new ContextManager(
                        ContextPolicy.builder()
                                .window(ContextWindow.of(exact - 1))
                                .keepRecent(2)
                                .build(),
                        (m, q) -> CompactionResult.of("nothing", m, m, q),
                        CharsPerTokenEstimator.INSTANCE,
                        0,
                        null);
        assertThatThrownBy(() -> withStrategy.prepare(finalIn, 0))
                .hasMessageContaining("even after compaction")
                .hasMessageNotContaining("no compaction strategy");
    }

    @Test
    void theNoteForAResultThatGainedTooLittleNamesThePercentage() {
        List<Message> in = convo(30, 400);
        ContextManagerTest.Recorder r = new ContextManagerTest.Recorder();
        ContextCompactor shy =
                (m, q) -> CompactionResult.of("shy", m, m.subList(0, m.size() - 1), q);
        new ContextManager(
                        policy((int) (size(in) * 1.1)), shy, CharsPerTokenEstimator.INSTANCE, 0, r)
                .prepare(in, 0);
        assertThat(r.compactions.get(0).result().note()).contains("at least 10 % smaller");
    }

    // ── the summariser ─────────────────────────────────────────────────────────────

    @Test
    void theTranscriptLabelsWhoSaidWhat() {
        List<Message> in =
                new Convo()
                        .system("s")
                        .task("the task")
                        .add(Message.user("a follow-up from the user"))
                        .add(
                                Message.assistantToolCalls(
                                        "   ",
                                        List.of(
                                                new io.github.llm4j.model.ToolCall(
                                                        "t1",
                                                        "lookup",
                                                        Map.of("zeta", 1, "alpha", 2))),
                                        Map.of()))
                        .add(Message.toolResult("t1", "lookup", "found it " + "x".repeat(900)))
                        .add(Message.assistant("a plain answer " + "y".repeat(900)))
                        .step("a", 900)
                        .step("b", 900)
                        .build();
        SummariserModel model = SummariserModel.saying("notes");
        ContextCompactors.summarize(model, 50)
                .compact(in, CompactionRequest.builder(100).keepRecent(2).build());
        String t = model.transcript();
        assertThat(t).contains("User: a follow-up from the user");
        assertThat(t).contains("Assistant: a plain answer");
        assertThat(t)
                .doesNotContain("Assistant: \n")
                .doesNotContain("Assistant:    "); // a blank thought is not written out
        assertThat(t)
                .contains(
                        "Assistant called lookup with {alpha=2, zeta=1}"); // arguments in a stable
        // order
        assertThat(t).contains("Result of lookup: found it");
    }

    @Test
    void anEarlierSummaryIsLabelledWithoutItsFixedIntroduction() {
        List<Message> first = convo(8, 700);
        CompactionResult one =
                ContextCompactors.summarize(SummariserModel.saying("Plan A costs 500."), 50)
                        .compact(
                                first,
                                CompactionRequest.builder(size(first) / 3).keepRecent(2).build());
        List<Message> grown = new ArrayList<>(one.messages());
        grown.addAll(new Convo().step("x", 700).step("y", 700).step("z", 700).build());
        SummariserModel model = SummariserModel.saying("merged");
        ContextCompactors.summarize(model, 50)
                .compact(grown, CompactionRequest.builder(size(grown) / 3).keepRecent(2).build());
        assertThat(model.transcript())
                .contains("Previous summary: Plan A costs 500.")
                .doesNotContain(Summarize.INTRO);
    }

    @Test
    void aMessageOfExactlyTheLimitIsSentWholeAndOneMoreIsCutAndCounted() {
        for (int[] c : new int[][] {{4_000, 0}, {4_001, 1}}) {
            List<Message> in =
                    new Convo()
                            .system("s")
                            .task("t")
                            .add(Message.toolResult("x", "t", "q".repeat(c[0])))
                            .step("a", 900)
                            .step("b", 900)
                            .build();
            SummariserModel model = SummariserModel.saying("notes");
            ContextCompactors.summarize(model, 50)
                    .compact(in, CompactionRequest.builder(100).keepRecent(2).build());
            if (c[1] == 0) assertThat(model.transcript()).doesNotContain("characters omitted");
            else assertThat(model.transcript()).contains("[… 1 characters omitted …]");
        }
    }

    @Test
    void aClientThatReturnsNothingAtAllIsTreatedAsAnEmptySummary() {
        List<Message> in = convo(10, 800);
        io.github.llm4j.LLMClient nothing =
                new io.github.llm4j.LLMClient() {
                    @Override
                    public LLMResponse chat(io.github.llm4j.model.LLMRequest request) {
                        return null;
                    }

                    @Override
                    public java.util.stream.Stream<LLMResponse> chatStream(
                            io.github.llm4j.model.LLMRequest request) {
                        return java.util.stream.Stream.empty();
                    }
                };
        CompactionResult r =
                ContextCompactors.summarize(nothing)
                        .compact(in, CompactionRequest.builder(size(in) / 2).keepRecent(2).build());
        assertThat(r.note()).contains("fell back to drop_oldest").contains("empty");
        assertThat(r.summarizerUsage()).isEqualTo(new CompactionUsage(1, 0, 0, null));
    }

    // ── small values ───────────────────────────────────────────────────────────────

    @Test
    void usageIsAddedUpWhateverIsMissing() {
        CompactionUsage a = new CompactionUsage(1, 10, 5, new BigDecimal("0.1"));
        CompactionUsage b = new CompactionUsage(2, 20, 7, null);
        assertThat(CompactionUsage.sum(null, null)).isNull();
        assertThat(CompactionUsage.sum(a, null)).isSameAs(a);
        assertThat(CompactionUsage.sum(null, b)).isSameAs(b);
        assertThat(CompactionUsage.sum(a, b))
                .isEqualTo(new CompactionUsage(3, 30, 12, new BigDecimal("0.1")));
        assertThat(CompactionUsage.sum(b, a).cost()).isEqualByComparingTo("0.1");
        assertThat(CompactionUsage.sum(a, a).cost()).isEqualByComparingTo("0.2");
    }

    @Test
    void aResultCanBeBuiltFromAStrategyObjectToo() {
        List<Message> in = convo(5, 800);
        ContextCompactor c = ContextCompactors.dropOldest();
        CompactionResult r = CompactionResult.of(c, in, in.subList(0, 4), CompactionRequest.of(1));
        assertThat(r.strategy()).isEqualTo("drop_oldest");
        assertThat(r.messagesAfter()).isEqualTo(4);
    }

    @Test
    void aWindowsLineWithNoModelNameSaysSo(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path f = dir.resolve("w.properties");
        java.nio.file.Files.writeString(f, "= 1000\n");
        assertThatThrownBy(() -> ContextWindows.load(f))
                .hasMessageContaining("the model name is missing");
        java.nio.file.Files.writeString(f, "no equals sign here\n");
        assertThatThrownBy(() -> ContextWindows.load(f))
                .hasMessageContaining("expected 'model = window");
    }

    @Test
    void aBadRequestKeepsWhateverStatusAndBodyItWasGiven() {
        InvalidRequestException bare = ContextOverflowClassifier.invalidRequest("m", null, null);
        assertThat(bare.getStatusCode()).isEqualTo(400);
        InvalidRequestException body =
                ContextOverflowClassifier.invalidRequest("m", null, "the body");
        assertThat(body.getStatusCode()).isEqualTo(400);
        assertThat(body.getResponseBody()).isEqualTo("the body");
        InvalidRequestException status = ContextOverflowClassifier.invalidRequest("m", 422, null);
        assertThat(status.getStatusCode()).isEqualTo(422);
        assertThat(status).isNotInstanceOf(ContextOverflowException.class);
    }

    @Test
    void theFixedOverheadCountsWhenDecidingWhetherACompactedPromptCanFit() {
        // after compaction the messages are small, but the system prompt and tool definitions (the
        // overhead) are not
        List<Message> in = convo(30, 400);
        ContextManager m =
                new ContextManager(
                        ContextPolicy.builder()
                                .window(ContextWindow.of(1_000))
                                .triggerAt(0.8)
                                .targetAt(0.5)
                                .keepRecent(2)
                                .build(),
                        ContextCompactors.dropOldest(),
                        CharsPerTokenEstimator.INSTANCE,
                        1_300,
                        null);
        assertThatThrownBy(() -> m.prepare(in, 0))
                .isInstanceOf(ContextOverflowException.class)
                .hasMessageContaining("even after compaction");
    }
}
