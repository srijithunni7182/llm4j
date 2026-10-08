package io.github.llm4j.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.budget.Budget;
import io.github.llm4j.budget.BudgetExceeded;
import io.github.llm4j.budget.BudgetPolicy;
import io.github.llm4j.budget.Window;
import io.github.llm4j.budget.fixtures.FixedEstimator;
import io.github.llm4j.budget.fixtures.ScriptedLLMClient;
import io.github.llm4j.budget.fixtures.Standard;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.routing.ProviderTier;
import io.github.llm4j.routing.RoutingLLMClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Verification plan V4.1–V4.4, plus routing across rate-limited tiers. */
class RateLimitedAgentTest {

    static final RateLimitInfo DAILY = new RateLimitInfo(Instant.parse("2026-09-28T07:00:00Z"),
            RateLimitInfo.Scope.DAILY_QUOTA, "google", "GenerateRequestsPerDay", null, null, false, "test");

    /** Answers with a tool turn, then throws a rate limit on the given call number (1-based). */
    static final class LimitedClient implements LLMClient {
        final AtomicInteger calls = new AtomicInteger();
        final int failOn;
        final RateLimitException error;

        LimitedClient(int failOn, RateLimitException error) {
            this.failOn = failOn;
            this.error = error;
        }

        @Override
        public LLMResponse chat(LLMRequest request) {
            if (calls.incrementAndGet() >= failOn) throw error;
            return LLMResponse.builder()
                    .content("```json\n{\"thought\": \"T1\", \"action\": \"none\", \"action_input\": {}}\n```")
                    .model("test/model").tokenUsage(100, 50, 150).build();
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            return Stream.of(chat(request));
        }
    }

    @Test
    void v4_1_providerLimitBecomesRateLimitedWithTheSameInfo() {
        LimitedClient model = new LimitedClient(2, new RateLimitException(DAILY, MutableClock.T0));
        ReActAgent agent = ReActAgent.builder().llmClient(model).maxIterations(5).build();
        assertThatThrownBy(() -> agent.run("go"))
                .isInstanceOfSatisfying(RateLimited.class, e -> {
                    assertThat(e.info()).isEqualTo(DAILY);
                    assertThat(e.reason()).isEqualTo(RateLimited.Reason.PROVIDER_LIMIT);
                    assertThat(e.resetAt()).isEqualTo(DAILY.resetAt());
                    assertThat(e.budgetExceeded()).isNull();
                });
        assertThat(model.calls.get()).isEqualTo(2);
    }

    @Test
    void v4_1b_legacyRateLimitExceptionIsEstimated() {
        LimitedClient model = new LimitedClient(1, new RateLimitException("slow down"));
        ReActAgent agent = ReActAgent.builder().llmClient(model).maxIterations(5).build();
        assertThatThrownBy(() -> agent.run("go"))
                .isInstanceOfSatisfying(RateLimited.class, e -> assertThat(e.info().estimated()).isTrue());
    }

    @Test
    void v4_2_windowedBudgetWithSuspendPolicyBecomesRateLimited() {
        MutableClock clock = new MutableClock();
        Budget hourly = Budget.builder().name("hourly").tokens(200).window(Window.HOUR).clock(clock).build();
        ScriptedLLMClient model = new ScriptedLLMClient(
                "```json\n{\"thought\": \"T1\", \"action\": \"none\", \"action_input\": {}}\n```");
        ReActAgent agent = ReActAgent.builder().llmClient(model).maxIterations(5).budget(hourly)
                .tokenEstimator(FixedEstimator.INSTANCE).maxTokensPerCall(50)
                .onBudgetExhausted(BudgetPolicy.SUSPEND).build();
        assertThatThrownBy(() -> agent.run("go"))
                .isInstanceOfSatisfying(RateLimited.class, e -> {
                    assertThat(e.reason()).isEqualTo(RateLimited.Reason.BUDGET_WINDOW);
                    assertThat(e.resetAt()).isEqualTo(Instant.parse("2026-09-27T11:00:00Z"));
                    assertThat(e.info().provider()).isEqualTo("budget:hourly");
                    assertThat(e.info().scope()).isEqualTo(RateLimitInfo.Scope.TOKENS);
                    assertThat(e.budgetExceeded()).isNotNull();
                });
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void v4_2b_suspendPolicyOnALifetimeBudgetReturnsThePartialAnswer() {
        Budget life = Budget.builder().name("life").tokens(200).build();
        ScriptedLLMClient model = new ScriptedLLMClient(
                "```json\n{\"thought\": \"T1\", \"action\": \"none\", \"action_input\": {}}\n```");
        AgentResult result = ReActAgent.builder().llmClient(model).maxIterations(5).budget(life)
                .tokenEstimator(FixedEstimator.INSTANCE).maxTokensPerCall(50)
                .onBudgetExhausted(BudgetPolicy.SUSPEND).build().run("go");
        assertThat(result.budgetExhausted()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("T1");
    }

    @Test
    void v4_3_lifetimeBudgetDefaultPolicyUnchanged() {
        Budget life = Budget.builder().name("life").tokens(200).build();
        ScriptedLLMClient model = new ScriptedLLMClient(
                "```json\n{\"thought\": \"T1\", \"action\": \"none\", \"action_input\": {}}\n```");
        AgentResult result = ReActAgent.builder().llmClient(model).maxIterations(5).budget(life)
                .tokenEstimator(FixedEstimator.INSTANCE).maxTokensPerCall(50).build().run("go");
        assertThat(result.budgetExhausted()).isTrue();
        assertThat(result.getBudgetExceeded()).isInstanceOf(BudgetExceeded.class);
    }

    @Test
    void v4_4_a429IsChargedAsACallWithNoTokens() {
        Budget run = Budget.unlimited("run");
        var client = Standard.client(new LimitedClient(1, new RateLimitException(DAILY, MutableClock.T0)), run);
        assertThatThrownBy(() -> client.chat(Standard.request())).isInstanceOf(RateLimitException.class);
        assertThat(run.spent().calls()).isEqualTo(1);
        assertThat(run.spent().tokens()).isZero();
        assertThat(run.spent().estimated()).isFalse();
    }

    @Test
    void routingReportsTheSoonestResetWhenEveryTierIsRateLimited() {
        RateLimitInfo soon = RateLimitInfo.at(Instant.parse("2026-09-27T10:05:00Z"), RateLimitInfo.Scope.REQUESTS, "a", "x");
        RateLimitInfo late = RateLimitInfo.at(Instant.parse("2026-09-27T12:00:00Z"), RateLimitInfo.Scope.REQUESTS, "b", "x");
        RoutingLLMClient routing = RoutingLLMClient.builder()
                .addClient(ProviderTier.BALANCED, new LimitedClient(1, new RateLimitException(late, MutableClock.T0)))
                .addClient(ProviderTier.FAST_CHEAP, new LimitedClient(1, new RateLimitException(soon, MutableClock.T0)))
                .build();
        assertThatThrownBy(() -> routing.chat(Standard.request()))
                .isInstanceOfSatisfying(RateLimitException.class, e -> assertThat(e.info()).isEqualTo(soon));

        assertThat(RoutingLLMClient.soonestIfAllRateLimited(List.of(
                new RateLimitException(late, MutableClock.T0), new LLMException("down")))).isNull();
        assertThat(RoutingLLMClient.soonestIfAllRateLimited(List.of())).isNull();
        RateLimitException legacy = new RateLimitException("x");
        assertThat(RoutingLLMClient.soonestIfAllRateLimited(List.of(legacy, new RateLimitException(soon, MutableClock.T0))).info())
                .isEqualTo(soon);
    }
}
