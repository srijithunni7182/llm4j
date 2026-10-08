package io.github.llm4j.eval.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.llm4j.LLMClient;
import io.github.llm4j.fairness.BiasEvent;
import io.github.llm4j.fairness.BiasMonitor;
import io.github.llm4j.fairness.BiasSeverity;
import io.github.llm4j.fairness.BiasType;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.List;
import org.assertj.core.api.Condition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LlmJudgePresetsTest {

    @Mock private LLMClient judgeClient;
    @Mock private BiasMonitor biasMonitor;

    private static LLMResponse verdict(int rating) {
        return LLMResponse.builder()
                .content(
                        String.format(
                                "```json%n{\"reasoning\": \"r\", \"rating\": %d}%n```", rating))
                .build();
    }

    @Test
    void correctness_passesExpectedOutputToPrompt() {
        when(judgeClient.chat(any(LLMRequest.class))).thenReturn(verdict(5));
        LlmJudgePresets presets = LlmJudgePresets.using(judgeClient);

        assertThat(presets.correctness("36").matches("The answer is 36.")).isTrue();

        ArgumentCaptor<LLMRequest> captor = ArgumentCaptor.forClass(LLMRequest.class);
        verify(judgeClient).chat(captor.capture());
        String userMessage = captor.getValue().getMessages().get(1).getContent();
        assertThat(userMessage).contains("36").contains("Expected Output");
    }

    @Test
    void answerRelevancy_passesInputToPrompt() {
        when(judgeClient.chat(any(LLMRequest.class))).thenReturn(verdict(5));
        LlmJudgePresets presets = LlmJudgePresets.using(judgeClient);

        assertThat(presets.answerRelevancy("What is 15% of 240?").matches("36")).isTrue();

        ArgumentCaptor<LLMRequest> captor = ArgumentCaptor.forClass(LLMRequest.class);
        verify(judgeClient).chat(captor.capture());
        assertThat(captor.getValue().getMessages().get(1).getContent())
                .contains("What is 15% of 240?");
    }

    @Test
    void faithfulnessAndGroundedness_areTheSameCheck() {
        when(judgeClient.chat(any(LLMRequest.class))).thenReturn(verdict(5));
        LlmJudgePresets presets = LlmJudgePresets.using(judgeClient);
        List<String> retrievalContext = List.of("Paris is the capital of France.");

        assertThat(presets.faithfulness(retrievalContext).matches("The capital is Paris."))
                .isTrue();
        assertThat(presets.groundedness(retrievalContext).matches("The capital is Paris."))
                .isTrue();
    }

    @Test
    void hallucinationFree_passesContextToPrompt() {
        when(judgeClient.chat(any(LLMRequest.class))).thenReturn(verdict(5));
        LlmJudgePresets presets = LlmJudgePresets.using(judgeClient);

        assertThat(presets.hallucinationFree(List.of("fact one")).matches("summary")).isTrue();
    }

    @Test
    void taskCompletion_passesInputToPrompt() {
        when(judgeClient.chat(any(LLMRequest.class))).thenReturn(verdict(5));
        LlmJudgePresets presets = LlmJudgePresets.using(judgeClient);

        assertThat(presets.taskCompletion("book me a flight").matches("Flight booked.")).isTrue();
    }

    @Test
    void toxicityAndBias_useDefaultThresholdOfHalf() {
        when(judgeClient.chat(any(LLMRequest.class))).thenReturn(verdict(2));
        LlmJudgePresets presets = LlmJudgePresets.using(judgeClient);

        assertThat(presets.toxicity().matches("some text")).isFalse();
        assertThat(presets.bias().matches("some text")).isFalse();
    }

    @Test
    void staticBiasPreset_delegatesToSuppliedBiasMonitor() {
        List<BiasEvent> events = List.of(highSeverityBiasEvent());
        when(biasMonitor.detectBias("biased text")).thenReturn(events);
        when(biasMonitor.shouldIntervene(events)).thenReturn(true);

        Condition<Object> condition = LlmJudgePresets.bias(biasMonitor);

        assertThat(condition.matches("biased text")).isFalse();
    }

    @Test
    void staticBiasPreset_passesWhenMonitorFindsNoBias() {
        when(biasMonitor.detectBias("clean text")).thenReturn(List.of());
        when(biasMonitor.shouldIntervene(List.of())).thenReturn(false);

        Condition<Object> condition = LlmJudgePresets.bias(biasMonitor);

        assertThat(condition.matches("clean text")).isTrue();
    }

    @Test
    void using_rejectsNullJudge() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> LlmJudgePresets.using(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static BiasEvent highSeverityBiasEvent() {
        return BiasEvent.builder()
                .type(BiasType.OTHER)
                .severity(BiasSeverity.HIGH)
                .text("biased text")
                .explanation("clearly biased")
                .build();
    }
}
