package io.github.llm4j.eval.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class JudgeResponseParserTest {

    @Test
    void parse_extractsRatingAndReasoningFromFencedJsonBlock() {
        String judgeOutput =
                """
                Sure, here is my evaluation:
                ```json
                {
                  "reasoning": "The answer is mostly correct.",
                  "rating": 4
                }
                ```
                """;

        JudgeVerdict verdict = JudgeResponseParser.parse(judgeOutput);

        assertThat(verdict.score()).isCloseTo(0.75, within(1e-9));
        assertThat(verdict.reason()).contains("4/5").contains("The answer is mostly correct.");
    }

    @Test
    void parse_mapsEveryRatingToItsNormalizedScore() {
        assertThat(JudgeResponseParser.parse(fenced(1, "worst")).score()).isCloseTo(0.0, within(1e-9));
        assertThat(JudgeResponseParser.parse(fenced(2, "poor")).score()).isCloseTo(0.25, within(1e-9));
        assertThat(JudgeResponseParser.parse(fenced(3, "ok")).score()).isCloseTo(0.5, within(1e-9));
        assertThat(JudgeResponseParser.parse(fenced(4, "good")).score()).isCloseTo(0.75, within(1e-9));
        assertThat(JudgeResponseParser.parse(fenced(5, "best")).score()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void parse_fallsBackToParsingWholeResponseWhenNoFence() {
        String judgeOutput = "{\"reasoning\": \"Perfect.\", \"rating\": 5}";

        JudgeVerdict verdict = JudgeResponseParser.parse(judgeOutput);

        assertThat(verdict.score()).isCloseTo(1.0, within(1e-9));
        assertThat(verdict.reason()).contains("Perfect.");
    }

    @Test
    void parse_defaultsReasoningToEmptyStringWhenMissing() {
        String judgeOutput = "```json\n{\"rating\": 2}\n```";

        JudgeVerdict verdict = JudgeResponseParser.parse(judgeOutput);

        assertThat(verdict.score()).isCloseTo(0.25, within(1e-9));
        assertThat(verdict.reason()).isEqualTo("[2/5] ");
    }

    @Test
    void parse_throwsWhenRatingFieldMissing() {
        String judgeOutput = "```json\n{\"reasoning\": \"no rating given\"}\n```";

        assertThatThrownBy(() -> JudgeResponseParser.parse(judgeOutput))
                .isInstanceOf(JudgeEvaluationException.class)
                .hasMessageContaining("rating");
    }

    @Test
    void parse_throwsWhenRatingIsOutOfRange() {
        assertThatThrownBy(() -> JudgeResponseParser.parse(fenced(0, "too low")))
                .isInstanceOf(JudgeEvaluationException.class)
                .hasMessageContaining("out-of-range rating");
        assertThatThrownBy(() -> JudgeResponseParser.parse(fenced(6, "too high")))
                .isInstanceOf(JudgeEvaluationException.class)
                .hasMessageContaining("out-of-range rating");
    }

    @Test
    void parse_throwsWhenNotJson() {
        assertThatThrownBy(() -> JudgeResponseParser.parse("I refuse to answer in JSON."))
                .isInstanceOf(JudgeEvaluationException.class);
    }

    @Test
    void parse_throwsWhenResponseIsBlank() {
        assertThatThrownBy(() -> JudgeResponseParser.parse("   "))
                .isInstanceOf(JudgeEvaluationException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void judgeVerdict_rejectsOutOfRangeScore() {
        assertThatThrownBy(() -> new JudgeVerdict(1.5, "too high"))
                .isInstanceOf(JudgeEvaluationException.class);
        assertThatThrownBy(() -> new JudgeVerdict(-0.1, "too low"))
                .isInstanceOf(JudgeEvaluationException.class);
    }

    private static String fenced(int rating, String reasoning) {
        return String.format(
                "```json%n{\"reasoning\": \"%s\", \"rating\": %d}%n```", reasoning, rating);
    }
}
