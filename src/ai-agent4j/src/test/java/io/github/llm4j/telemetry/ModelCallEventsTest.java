package io.github.llm4j.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.budget.BudgetedLLMClient;
import io.github.llm4j.context.ContextCompactors;
import io.github.llm4j.context.ContextPolicy;
import io.github.llm4j.context.ContextWindow;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.ContextOverflowException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.ToolCall;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * One event per model call, in both loops, whether the call produced an answer or not (design §4.2,
 * R1 to R5, R23).
 */
class ModelCallEventsTest {

    private static LLMResponse answer(
            boolean nativeLoop, String model, int in, int out, String finish) {
        return LLMResponse.builder()
                .content(nativeLoop ? "done" : "Thought: ok\nFinal Answer: done")
                .model(model)
                .tokenUsage(in, out, in + out)
                .finishReason(finish)
                .build();
    }

    private static LLMResponse toolStep(
            boolean nativeLoop, String model, int in, int out, String q) {
        if (nativeLoop) {
            return LLMResponse.builder()
                    .content("go")
                    .model(model)
                    .tokenUsage(in, out, in + out)
                    .finishReason("tool_calls")
                    .toolCalls(List.of(new ToolCall("c-" + q, "t", Map.of("q", q))))
                    .build();
        }
        return LLMResponse.builder()
                .content("Thought: go\nAction: t\nAction Input: {\"q\":\"" + q + "\"}\n")
                .model(model)
                .tokenUsage(in, out, in + out)
                .build();
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void aCallThatSucceeds(boolean nativeLoop) {
        TestClock clock = new TestClock();
        TelemetryListener l = new TelemetryListener();
        LLMClient model =
                Scripts.by(
                        nativeLoop,
                        n -> {
                            clock.advance(700);
                            return n == 0
                                    ? toolStep(nativeLoop, "gemini-2.5-flash", 100, 20, "a")
                                    : answer(nativeLoop, "gemini-2.5-flash", 140, 30, "stop");
                        });
        ReActAgent.builder()
                .llmClient(model)
                .addTool(TestTool.returning("t", "ok"))
                .clock(clock)
                .addListener(l)
                .build()
                .run("Go.");

        assertThat(l.models).hasSize(2);
        ModelCallEvent first = l.models.get(0);
        assertThat(first.iteration()).isZero();
        assertThat(first.model()).isEqualTo("gemini-2.5-flash");
        assertThat(first.requestedModel()).isNull();
        assertThat(first.outcome()).isEqualTo(ModelCallEvent.Outcome.OK);
        assertThat(first.failed()).isFalse();
        assertThat(first.durationMillis()).isEqualTo(700);
        assertThat(first.waitedMillis()).isZero();
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(first.failovers()).isEmpty();
        assertThat(first.promptTokens()).isEqualTo(100);
        assertThat(first.completionTokens()).isEqualTo(20);
        assertThat(first.totalTokens()).isEqualTo(120);
        assertThat(first.startedAt()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(first.toolCallsRequested()).isEqualTo(nativeLoop ? 1 : 0);
        assertThat(first.category()).isNull();
        ModelCallEvent second = l.models.get(1);
        assertThat(second.iteration()).isEqualTo(1);
        assertThat(second.finishReason()).isEqualTo(LLMResponse.FinishReason.STOP);
        assertThat(second.toolCallsRequested()).isZero();
        assertThat(second.promptTokens()).isEqualTo(140);
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void theModelThatAnsweredIsTheOneRecordedWhateverTheAgentAskedFor(boolean nativeLoop) {
        TelemetryListener l = new TelemetryListener();
        // the agent does not name a model in its requests: whoever answers says who it was (a
        // routing client may have used another)
        LLMClient model =
                Scripts.by(
                        nativeLoop, n -> answer(nativeLoop, "the-fallback-model", 10, 5, "stop"));
        ReActAgent.builder()
                .llmClient(model)
                .addTool(TestTool.returning("t", "x"))
                .addListener(l)
                .build()
                .run("Go.");
        assertThat(l.models).hasSize(1);
        assertThat(l.models.get(0).model()).isEqualTo("the-fallback-model");
        assertThat(l.models.get(0).requestedModel()).isNull();
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void costAndEstimatedUsageComeFromTheResponse(boolean nativeLoop) {
        TelemetryListener l = new TelemetryListener();
        LLMClient model =
                Scripts.by(
                        nativeLoop,
                        n ->
                                LLMResponse.builder()
                                        .content(
                                                nativeLoop
                                                        ? "done"
                                                        : "Thought: ok\nFinal Answer: done")
                                        .model("m")
                                        .tokenUsage(50, 5, 55)
                                        .finishReason("stop")
                                        .addMetadata(
                                                BudgetedLLMClient.COST, new BigDecimal("0.0042"))
                                        .addMetadata(BudgetedLLMClient.ESTIMATED, true)
                                        .build());
        ReActAgent.builder()
                .llmClient(model)
                .addTool(TestTool.returning("t", "x"))
                .addListener(l)
                .build()
                .run("Go.");
        assertThat(l.models.get(0).cost()).isEqualByComparingTo("0.0042");
        assertThat(l.models.get(0).usageEstimated()).isTrue();
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void anAnswerCutOffByTheTokenLimitIsTruncated(boolean nativeLoop) {
        TelemetryListener l = new TelemetryListener();
        LLMClient model = Scripts.by(nativeLoop, n -> answer(nativeLoop, "m", 10, 5, "length"));
        AgentResult r =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(TestTool.returning("t", "x"))
                        .addListener(l)
                        .build()
                        .run("Go.");
        assertThat(l.models.get(0).truncated()).isTrue();
        assertThat(l.models.get(0).finishReason()).isEqualTo(LLMResponse.FinishReason.LENGTH);
        assertThat(r.getStats().truncatedAnswers()).isEqualTo(1);
        assertThat(r.getStats().model("m").finishReasons())
                .containsEntry(LLMResponse.FinishReason.LENGTH, 1L);
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void aCallThatFailsIsReportedAndTheExceptionStillPropagates(boolean nativeLoop) {
        Object[][] cases = {
            {
                new ProviderException("p", "unavailable", 503),
                ErrorCategory.SERVER_ERROR,
                "ProviderException"
            },
            {
                new AuthenticationException("bad key"),
                ErrorCategory.AUTHENTICATION,
                "AuthenticationException"
            },
            {new IllegalStateException("odd"), ErrorCategory.OTHER, "IllegalStateException"},
        };
        for (Object[] c : cases) {
            TestClock clock = new TestClock();
            TelemetryListener l = new TelemetryListener();
            RuntimeException thrown = (RuntimeException) c[0];
            LLMClient model =
                    Scripts.by(
                            nativeLoop,
                            n -> {
                                clock.advance(300);
                                throw thrown;
                            });
            ReActAgent agent =
                    ReActAgent.builder()
                            .llmClient(model)
                            .addTool(TestTool.returning("t", "x"))
                            .clock(clock)
                            .addListener(l)
                            .build();
            assertThatThrownBy(() -> agent.run("Go.")).isSameAs(thrown);
            assertThat(l.models).hasSize(1);
            ModelCallEvent e = l.models.get(0);
            assertThat(e.failed()).isTrue();
            assertThat(e.category()).isEqualTo(c[1]);
            assertThat(e.errorType()).isEqualTo(c[2]);
            assertThat(e.durationMillis()).isEqualTo(300);
            assertThat(e.model()).isNull();
            assertThat(e.finishReason()).isNull();
            assertThat(e.promptTokens()).isZero();
        }
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void aRateLimitIsReportedAsOneThenHandedToTheCallerAsAlways(boolean nativeLoop) {
        TelemetryListener l = new TelemetryListener();
        LLMClient model =
                Scripts.by(
                        nativeLoop,
                        n -> {
                            throw new RateLimitException("slow down", 90L);
                        });
        ReActAgent agent =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(TestTool.returning("t", "x"))
                        .addListener(l)
                        .build();
        assertThatThrownBy(() -> agent.run("Go."))
                .isInstanceOf(io.github.llm4j.ratelimit.RateLimited.class);
        assertThat(l.models).hasSize(1);
        assertThat(l.models.get(0).category()).isEqualTo(ErrorCategory.RATE_LIMITED);
        assertThat(l.models.get(0).errorType()).isEqualTo("RateLimitException");
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void aCallARefusingBudgetStoppedIsAFailedCallInTheResultsStats(boolean nativeLoop) {
        TelemetryListener l = new TelemetryListener();
        io.github.llm4j.budget.Budget budget =
                io.github.llm4j.budget.Budget.builder().calls(2).build();
        AgentResult r =
                ReActAgent.builder()
                        .llmClient(Scripts.toolSteps(nativeLoop, "t", 50))
                        .addTool(TestTool.returning("t", "ok"))
                        .budget(budget)
                        .maxIterations(20)
                        .addListener(l)
                        .build()
                        .run("Go.");
        assertThat(r.budgetExhausted()).isTrue();
        ModelCallEvent last = l.models.get(l.models.size() - 1);
        assertThat(last.failed()).isTrue();
        assertThat(last.category()).isEqualTo(ErrorCategory.BUDGET);
        assertThat(last.errorType()).isEqualTo("BudgetExceeded");
        assertThat(r.getStats().totalModelFailures()).isEqualTo(1);
        assertThat(r.getStats().totalModelCalls()).isEqualTo(l.models.size());
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void aRetryAfterAContextOverflowIsASecondEvent(boolean nativeLoop) {
        TelemetryListener l = new TelemetryListener();
        ContextOverflowException overflow =
                new ContextOverflowException("prompt is too long", 400, "{}");
        AtomicInteger calls = new AtomicInteger();
        LLMClient model =
                Scripts.by(
                        nativeLoop,
                        n -> {
                            calls.incrementAndGet();
                            if (n == 5) throw overflow;
                            return n < 8
                                    ? toolStep(nativeLoop, "m", 100, 10, "q" + n)
                                    : answer(nativeLoop, "m", 100, 10, "stop");
                        });
        ContextPolicy policy =
                ContextPolicy.builder().window(ContextWindow.of(1_000_000)).keepRecent(2).build();
        AgentResult r =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(TestTool.returning("t", "x".repeat(800)))
                        .maxIterations(50)
                        .contextPolicy(policy)
                        .compaction(ContextCompactors.dropOldest())
                        .addListener(l)
                        .build()
                        .run("Go.");
        assertThat(r.getFinalAnswer()).isEqualTo("done");
        assertThat(l.models).hasSize(calls.get());
        assertThat(l.models.get(5).failed()).isTrue();
        assertThat(l.models.get(5).category()).isEqualTo(ErrorCategory.CONTEXT_OVERFLOW);
        assertThat(l.models.get(6).failed()).isFalse();
        assertThat(l.models.get(6).iteration())
                .as("the retry belongs to the same iteration")
                .isEqualTo(l.models.get(5).iteration());
        // a failed call has no answering model, so the statistics file it under "unknown"
        assertThat(r.getStats().model("unknown").failures())
                .containsEntry(ErrorCategory.CONTEXT_OVERFLOW, 1L);
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void theStatisticsOfTheResultAreTheModelEventsAddedUp(boolean nativeLoop) {
        TestClock clock = new TestClock();
        TelemetryListener l = new TelemetryListener();
        LLMClient model =
                Scripts.by(
                        nativeLoop,
                        n -> {
                            clock.advance(100L * (n + 1));
                            return n < 3
                                    ? toolStep(nativeLoop, "m", 100 + n, 10, "q" + n)
                                    : answer(nativeLoop, "m", 200, 40, "stop");
                        });
        AgentResult r =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(TestTool.returning("t", "x"))
                        .clock(clock)
                        .addListener(l)
                        .maxIterations(20)
                        .build()
                        .run("Go.");
        ModelStats m = r.getStats().model("m");
        assertThat(m.calls()).isEqualTo(4);
        assertThat(m.promptTokens()).isEqualTo(100 + 101 + 102 + 200);
        assertThat(m.completionTokens()).isEqualTo(10 + 10 + 10 + 40);
        assertThat(m.latency().count()).isEqualTo(4);
        assertThat(m.latency().min()).isEqualTo(100);
        assertThat(m.latency().max()).isEqualTo(400);
        assertThat(m.successRate()).hasValue(1.0);
        StatsAccumulator replay = new StatsAccumulator();
        l.models.forEach(replay::onModelCall);
        assertThat(r.getStats().models()).isEqualTo(replay.snapshot().models());
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void aSummariserCallIsNotAModelCallOfTheRun(boolean nativeLoop) {
        AtomicInteger summaries = new AtomicInteger();
        LLMClient summariser =
                Scripts.by(
                        false,
                        n -> {
                            summaries.incrementAndGet();
                            return LLMResponse.builder()
                                    .content("Notes.")
                                    .model("cheap")
                                    .tokenUsage(500, 20, 520)
                                    .build();
                        });
        AtomicInteger mainCalls = new AtomicInteger();
        LLMClient model =
                Scripts.by(
                        nativeLoop,
                        n -> {
                            mainCalls.incrementAndGet();
                            return n < 14
                                    ? toolStep(nativeLoop, "m", 250 * (n + 1), 10, "q" + n)
                                    : answer(nativeLoop, "m", 250 * (n + 1), 10, "stop");
                        });
        TelemetryListener l = new TelemetryListener();
        AgentResult r =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(TestTool.returning("t", "x".repeat(1_200)))
                        .maxIterations(50)
                        .contextPolicy(
                                ContextPolicy.builder()
                                        .window(ContextWindow.of(4_000))
                                        .keepRecent(2)
                                        .build())
                        .compaction(ContextCompactors.summarize(summariser, 50))
                        .addListener(l)
                        .build()
                        .run("Go.");
        assertThat(summaries.get()).as("the summariser was used").isGreaterThan(0);
        assertThat(l.models).hasSize(mainCalls.get());
        assertThat(r.getStats().models()).containsOnlyKeys("m");
        assertThat(r.getStats().totalModelCalls()).isEqualTo(mainCalls.get());
        assertThat(r.getUsage().getLlmCalls())
                .as("the summaries stay in the usage totals, as in Phase 1")
                .isEqualTo(mainCalls.get() + summaries.get());
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void theEventsAreDeliveredInOrderBeforeTheNextStep(boolean nativeLoop) {
        java.util.List<String> order = new java.util.ArrayList<>();
        io.github.llm4j.agent.AgentEventListener l =
                new io.github.llm4j.agent.AgentEventListener() {
                    @Override
                    public void onThought(String t) {}

                    @Override
                    public void onAction(String a, String i) {
                        order.add("action");
                    }

                    @Override
                    public void onObservation(String o) {
                        order.add("observation");
                    }

                    @Override
                    public void onModelCall(ModelCallEvent e) {
                        order.add("model" + e.iteration());
                    }

                    @Override
                    public void onToolCall(ToolCallEvent e) {
                        order.add("tool" + e.iteration());
                    }
                };
        ReActAgent.builder()
                .llmClient(Scripts.toolSteps(nativeLoop, "t", 2))
                .addTool(TestTool.returning("t", "x"))
                .addListener(l)
                .maxIterations(10)
                .build()
                .run("Go.");
        assertThat(order)
                .containsSubsequence(
                        "model0",
                        "action",
                        "observation",
                        "tool0",
                        "model1",
                        "action",
                        "observation",
                        "tool1",
                        "model2");
    }
}
