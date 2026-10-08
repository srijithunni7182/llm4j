package io.github.llm4j.eval.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.llm4j.eval.support.StubJudge;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Bounded scale checks: large inputs finish quickly, make exactly the documented number of judge
 * calls, and don't send the whole transcript with every call.
 */
class ScaleSmokeTest {

    @Test
    void twoHundredChunks_oneJudgeCallEach_finishesQuickly() {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            chunks.add((i % 2 == 0 ? "GOOD " : "junk ") + i);
        }
        StubJudge judge = StubJudge.rating(m -> m.contains("GOOD") ? 5 : 1);
        JudgeVerdict v =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(10),
                        () ->
                                LlmJudgePresets.using(judge)
                                        .contextualRelevancy("q", chunks)
                                        .evaluate());
        assertThat(v.score()).isEqualTo(0.5);
        assertThat(judge.callCount()).isEqualTo(200);
    }

    @Test
    void fiveHundredTurnTranscript_isWindowedPerCall_notSentWholeEveryTime() {
        Transcript.Builder b = Transcript.builder();
        for (int i = 0; i < 500; i++) {
            b.user("question " + i + " " + "pad ".repeat(10)).assistant("answer " + i);
        }
        Transcript transcript = b.build();
        StubJudge judge = StubJudge.rating(m -> 5);
        JudgeVerdict v =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(10),
                        () ->
                                ConversationJudgePresets.using(judge)
                                        .conversationRelevancy()
                                        .evaluate(transcript));
        assertThat(v.score()).isEqualTo(1.0);
        assertThat(judge.callCount()).isEqualTo(500);
        long largest =
                judge.requests().stream()
                        .mapToLong(r -> StubJudge.userMessage(r).length())
                        .max()
                        .orElse(0);
        assertThat(largest).as("each relevancy call sees only a small window").isLessThan(2_000);
    }
}
