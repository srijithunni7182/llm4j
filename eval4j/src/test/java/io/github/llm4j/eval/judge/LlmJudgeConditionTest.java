package io.github.llm4j.eval.judge;

import static io.github.llm4j.eval.judge.LlmJudgeCondition.llmJudged;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LlmJudgeConditionTest {

    @Mock private LLMClient judgeClient;

    private static LLMResponse fencedJsonResponse(int rating, String reasoning) {
        String content =
                String.format(
                        "```json%n{\"reasoning\": \"%s\", \"rating\": %d}%n```", reasoning, rating);
        return LLMResponse.builder().content(content).build();
    }

    @Test
    void matches_returnsTrueWhenScoreMeetsThreshold() {
        when(judgeClient.chat(any(LLMRequest.class)))
                .thenReturn(fencedJsonResponse(4, "Good answer")); // rating 4 -> score 0.75

        LlmJudgeCondition condition =
                llmJudged("Correctness")
                        .criteria("Is the answer correct?")
                        .judge(judgeClient)
                        .threshold(0.7)
                        .build();

        assertThat(condition.matches("The answer is 36.")).isTrue();
    }

    @Test
    void matches_returnsFalseWhenScoreBelowThreshold() {
        when(judgeClient.chat(any(LLMRequest.class)))
                .thenReturn(fencedJsonResponse(2, "Wrong answer")); // rating 2 -> score 0.25

        LlmJudgeCondition condition =
                llmJudged("Correctness")
                        .criteria("Is the answer correct?")
                        .judge(judgeClient)
                        .threshold(0.7)
                        .build();

        assertThat(condition.matches("The answer is 42.")).isFalse();
    }

    @Test
    void descriptionIncludesJudgeReasonAfterMatching() {
        when(judgeClient.chat(any(LLMRequest.class)))
                .thenReturn(fencedJsonResponse(1, "Missing key facts")); // rating 1 -> score 0.0

        LlmJudgeCondition condition =
                llmJudged("Correctness")
                        .criteria("Is the answer correct?")
                        .judge(judgeClient)
                        .threshold(0.7)
                        .build();

        condition.matches("wrong");

        assertThat(condition.description().value()).contains("Missing key facts").contains("0.00");
    }

    @Test
    void matches_extractsFinalAnswerFromAgentResult() {
        when(judgeClient.chat(any(LLMRequest.class))).thenReturn(fencedJsonResponse(5, "ok"));
        AgentResult result =
                AgentResult.builder().finalAnswer("36").completed(true).iterations(1).build();

        LlmJudgeCondition condition =
                llmJudged("Correctness").criteria("x").judge(judgeClient).threshold(0.5).build();

        assertThat(condition.matches(result)).isTrue();
    }

    @Test
    void matches_extractsContentFromLlmResponse() {
        when(judgeClient.chat(any(LLMRequest.class))).thenReturn(fencedJsonResponse(5, "ok"));
        LLMResponse response = LLMResponse.builder().content("36").build();

        LlmJudgeCondition condition =
                llmJudged("Correctness").criteria("x").judge(judgeClient).threshold(0.5).build();

        assertThat(condition.matches(response)).isTrue();
    }

    @Test
    void matches_throwsIllegalArgumentForUnsupportedType() {
        LlmJudgeCondition condition =
                llmJudged("Correctness").criteria("x").judge(judgeClient).threshold(0.5).build();

        assertThatThrownBy(() -> condition.matches(42))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matches_wrapsJudgeCallFailureInJudgeEvaluationException() {
        when(judgeClient.chat(any(LLMRequest.class))).thenThrow(new RuntimeException("network down"));

        LlmJudgeCondition condition =
                llmJudged("Correctness").criteria("x").judge(judgeClient).threshold(0.5).build();

        assertThatThrownBy(() -> condition.matches("anything"))
                .isInstanceOf(JudgeEvaluationException.class)
                .hasMessageContaining("Correctness");
    }

    @Test
    void builder_requiresCriteriaAndJudge() {
        assertThatThrownBy(() -> llmJudged("x").judge(judgeClient).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> llmJudged("x").criteria("y").build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_rejectsSamplesBelowOne() {
        assertThatThrownBy(() -> llmJudged("x").samples(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void singleSample_usesTemperatureZero() {
        when(judgeClient.chat(any(LLMRequest.class))).thenReturn(fencedJsonResponse(5, "ok"));
        ArgumentCaptor<LLMRequest> captor = ArgumentCaptor.forClass(LLMRequest.class);

        LlmJudgeCondition condition =
                llmJudged("Correctness").criteria("x").judge(judgeClient).threshold(0.5).build();
        condition.matches("36");

        verify(judgeClient).chat(captor.capture());
        assertThat(captor.getValue().getTemperature()).isEqualTo(0.0);
    }

    @Test
    void multipleSamples_areAveragedAndUseHigherTemperature() {
        when(judgeClient.chat(any(LLMRequest.class)))
                .thenReturn(fencedJsonResponse(3, "so-so")) // 0.5
                .thenReturn(fencedJsonResponse(5, "great")) // 1.0
                .thenReturn(fencedJsonResponse(1, "bad")); // 0.0
        ArgumentCaptor<LLMRequest> captor = ArgumentCaptor.forClass(LLMRequest.class);

        LlmJudgeCondition condition =
                llmJudged("Correctness")
                        .criteria("x")
                        .judge(judgeClient)
                        .threshold(0.4)
                        .samples(3)
                        .build();

        boolean matched = condition.matches("36"); // average = (0.5 + 1.0 + 0.0) / 3 = 0.5

        verify(judgeClient, times(3)).chat(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(r -> assertThat(r.getTemperature()).isEqualTo(0.7));
        assertThat(matched).isTrue();
        assertThat(condition.description().value()).contains("0.50").contains("Averaged over 3 samples");
    }

    @Test
    void cache_avoidsRepeatingAnIdenticalJudgeCall() {
        JudgeCache cache = InMemoryJudgeCache.create();
        when(judgeClient.chat(any(LLMRequest.class))).thenReturn(fencedJsonResponse(4, "good"));

        LlmJudgeCondition condition =
                llmJudged("Correctness")
                        .criteria("x")
                        .judge(judgeClient)
                        .threshold(0.5)
                        .cache(cache)
                        .build();

        assertThat(condition.matches("36")).isTrue();
        assertThat(condition.matches("36")).isTrue();

        verify(judgeClient, times(1)).chat(any(LLMRequest.class));
    }

    @Test
    void cache_stillCallsJudgeForADifferentActualOutput() {
        JudgeCache cache = InMemoryJudgeCache.create();
        when(judgeClient.chat(any(LLMRequest.class)))
                .thenReturn(fencedJsonResponse(4, "good"))
                .thenReturn(fencedJsonResponse(1, "bad"));

        LlmJudgeCondition condition =
                llmJudged("Correctness")
                        .criteria("x")
                        .judge(judgeClient)
                        .threshold(0.5)
                        .cache(cache)
                        .build();

        assertThat(condition.matches("36")).isTrue();
        assertThat(condition.matches("42")).isFalse();

        verify(judgeClient, times(2)).chat(any(LLMRequest.class));
    }

    @Test
    void cache_hitsAreAppliedPerSampleSoRerunsReplaySameDrawsWithoutNewCalls() {
        JudgeCache cache = InMemoryJudgeCache.create();
        when(judgeClient.chat(any(LLMRequest.class)))
                .thenReturn(fencedJsonResponse(3, "a"))
                .thenReturn(fencedJsonResponse(5, "b"))
                .thenReturn(fencedJsonResponse(1, "c"));

        LlmJudgeCondition condition =
                llmJudged("Correctness")
                        .criteria("x")
                        .judge(judgeClient)
                        .threshold(0.4)
                        .samples(3)
                        .cache(cache)
                        .build();

        boolean firstRun = condition.matches("36");
        boolean secondRun = condition.matches("36");

        assertThat(firstRun).isEqualTo(secondRun);
        assertThat(condition.description().value()).contains("0.50");
        verify(judgeClient, times(3)).chat(any(LLMRequest.class));
    }

    @Test
    void cache_missReturnsEmptyByDefault() {
        JudgeCache cache = InMemoryJudgeCache.create();
        assertThat(cache.get("unknown-key")).isEmpty();
    }

    @Test
    void evaluate_returnsVerdictWithoutAsserting() {
        when(judgeClient.chat(any(LLMRequest.class)))
                .thenReturn(fencedJsonResponse(2, "Weak hook")); // rating 2 -> score 0.25

        LlmJudgeCondition condition =
                llmJudged("Hook strength")
                        .criteria("The first line stops the scroll")
                        .judge(judgeClient)
                        .threshold(0.7)
                        .build();

        JudgeVerdict verdict = condition.evaluate("Here are some tips.");

        assertThat(verdict.score()).isEqualTo(0.25);
        assertThat(verdict.reason()).contains("Weak hook");
        assertThat(condition.getName()).isEqualTo("Hook strength");
        assertThat(condition.getThreshold()).isEqualTo(0.7);
    }
}
