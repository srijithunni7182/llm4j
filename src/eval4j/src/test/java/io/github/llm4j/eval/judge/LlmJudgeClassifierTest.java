package io.github.llm4j.eval.judge;

import static io.github.llm4j.eval.judge.LlmJudgeCondition.llmJudged;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.classify.ClassifyRequest;
import io.github.llm4j.classify.Question;
import io.github.llm4j.classify.ClassifyUsage;
import io.github.llm4j.eval.export.JudgeTelemetry;
import io.github.llm4j.eval.testing.FakeJudge;
import io.github.llm4j.eval.testing.ScriptedClassifier;
import io.github.llm4j.exception.RateLimitException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code llmJudged(...).classifier(...)}: a judgement as one typed question (R10). */
class LlmJudgeClassifierTest {

    @BeforeEach
    void clean() {
        JudgeTelemetry.drain();
    }

    private static LlmJudgeCondition.Builder judged(ScriptedClassifier c) {
        return llmJudged("Correctness").criteria("The answer states the refund window as 30 days").classifier(c);
    }

    // ---- passing and failing at the threshold

    @Test
    void passesAtOrAboveTheThresholdAndFailsBelow() {
        assertThat(judged(ScriptedClassifier.builder().yesNo("criterion", 0.8).build()).threshold(0.7).build().matches("30 days")).isTrue();
        assertThat(judged(ScriptedClassifier.builder().yesNo("criterion", 0.7).build()).threshold(0.7).build().matches("30 days")).isTrue();
        assertThat(judged(ScriptedClassifier.builder().yesNo("criterion", 0.6).build()).threshold(0.7).build().matches("30 days")).isFalse();
    }

    @Test
    void theScoreIsTheProbabilityOfYes() {
        JudgeVerdict v = judged(ScriptedClassifier.builder().yesNo("criterion", 0.43).build()).build().evaluate("the answer");

        assertThat(v.score()).isEqualTo(0.43);
        assertThat(v.reason()).isEmpty();
    }

    @Test
    void aGradedCriterionIsAFivePointScaleAndTheScoreIsThePosition() {
        ScriptedClassifier c = ScriptedClassifier.builder().score("criterion", 0.75).build();

        JudgeVerdict v = judged(c).graded(true).build().evaluate("the answer");

        assertThat(v.score()).isEqualTo(0.75);
        Question q = c.requests().get(0).questions().get(0);
        assertThat(q).isInstanceOf(Question.Score.class);
        assertThat(((Question.Score) q).scale().size()).isEqualTo(5);
    }

    // ---- what the classifier is asked

    @Test
    void theStateCarriesTheOutputAndOnlyThePartsThatWereGiven() {
        ScriptedClassifier c = ScriptedClassifier.builder().yesNo("criterion", 0.9).build();

        judged(c).input("What is the refund window?").expectedOutput("30 days").context(List.of("policy v2")).retrievalContext(List.of("chunk a", "chunk b"))
                .build().evaluate("It is 30 days.");

        ClassifyRequest sent = c.requests().get(0);
        assertThat(sent.state().keySet()).containsExactly("input", "expected_output", "context", "retrieved_context", "actual_output");
        assertThat(sent.state().get("actual_output")).isEqualTo("It is 30 days.");
        assertThat(sent.state().get("retrieved_context")).isEqualTo("- chunk a\n- chunk b");
        assertThat(sent.questions()).hasSize(1);
        assertThat(sent.questions().get(0).instructions()).contains("Correctness").contains("30 days");
    }

    @Test
    void onlyTheOutputIsSentWhenNothingElseIsGiven() {
        ScriptedClassifier c = ScriptedClassifier.builder().yesNo("criterion", 0.9).build();

        judged(c).build().evaluate("just this");

        assertThat(c.requests().get(0).state().keySet()).containsExactly("actual_output");
    }

    // ---- samples, cache, telemetry, dimensions

    @Test
    void samplesAreAveraged() {
        ScriptedClassifier c = ScriptedClassifier.builder().yesNo("criterion", 0.9).next().yesNo("criterion", 0.6).next().yesNo("criterion", 0.3).build();

        JudgeVerdict v = judged(c).samples(3).build().evaluate("x");

        assertThat(v.score()).isEqualTo(0.6, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(c.requests()).hasSize(3);
    }

    @Test
    void aCacheReplaysTheSameCallAndAsksAgainForNewInput() {
        ScriptedClassifier c = ScriptedClassifier.builder().yesNo("criterion", 0.9).build();
        LlmJudgeCondition cond = judged(c).cache(InMemoryJudgeCache.create()).build();

        cond.evaluate("same");
        JudgeTelemetry.Usage first = JudgeTelemetry.drain();
        cond.evaluate("same");
        JudgeTelemetry.Usage second = JudgeTelemetry.drain();
        cond.evaluate("different");

        assertThat(c.requests()).hasSize(2);
        assertThat(first.calls()).isEqualTo(1);
        assertThat(second.servedFromCache()).isTrue();
    }

    @Test
    void theCacheDoesNotMixClassifierAndChatVerdicts() {
        JudgeCache shared = InMemoryJudgeCache.create();
        ScriptedClassifier c = ScriptedClassifier.builder().yesNo("criterion", 0.1).build();
        LLMClient chat = FakeJudge.rating(5);

        double viaChat = llmJudged("Correctness").criteria("The answer states the refund window as 30 days").judge(chat).cache(shared).judgeIdentifier("j")
                .build().evaluate("x").score();
        double viaClassifier = judged(c).cache(shared).judgeIdentifier("j").build().evaluate("x").score();

        assertThat(viaChat).isEqualTo(1.0);
        assertThat(viaClassifier).isEqualTo(0.1);
    }

    @Test
    void telemetryCountsTheCallWithItsTokens() {
        ScriptedClassifier c = ScriptedClassifier.builder().usage(new ClassifyUsage(120, 7, 180, "jev", "jev-1")).yesNo("criterion", 0.9).build();

        judged(c).build().evaluate("x");
        JudgeTelemetry.Usage u = JudgeTelemetry.drain();

        assertThat(u.calls()).isEqualTo(1);
        assertThat(u.tokensIn()).isEqualTo(120);
        assertThat(u.tokensOut()).isEqualTo(7);
    }

    @Test
    void dimensionsAreFiledFromOneCall() {
        ScriptedClassifier c = ScriptedClassifier.builder().yesNo("criterion", 0.9).build();

        boolean ok = judged(c).dimensions("fact-checking", "safety").build().matches("x");

        assertThat(ok).isTrue();
        assertThat(c.requests()).hasSize(1);
    }

    // ---- failure text (no invented reason)

    @Test
    void theFailureMessageNamesTheCriterionScoreAndThresholdWithoutInventingAReason() {
        ScriptedClassifier c = ScriptedClassifier.builder().yesNo("criterion", 0.2).build();
        LlmJudgeCondition cond = judged(c).threshold(0.7).build();

        cond.matches("x");

        assertThat(cond.description().value()).isEqualTo("llm-judged \"Correctness\" (score=0.20, threshold=0.70)");
    }

    @Test
    void aRationaleBecomesTheReasonWhenTheBackendGaveOne() {
        LLMClient chat = new io.github.llm4j.eval.testing.ScriptedClient().otherwise(
                "{\"answers\": {\"criterion\": {\"answer\": \"no\", \"confidence\": 0.9, \"reason\": \"it says 14 days\"}}}");
        io.github.llm4j.classify.Classifier c = io.github.llm4j.classify.LlmClassifier.builder(chat).withRationale(true).build();
        LlmJudgeCondition cond = llmJudged("Correctness").criteria("states 30 days").classifier(c).threshold(0.7).build();

        cond.matches("14 days");

        assertThat(cond.description().value()).contains("score=0.10").contains("it says 14 days");
    }

    @Test
    void aFailingClassifierIsAJudgeErrorWithTheCauseNotAFailureToMatch() {
        ScriptedClassifier c = ScriptedClassifier.builder().failWith(new RateLimitException("slow down")).build();

        assertThatThrownBy(() -> judged(c).build().matches("x")).isInstanceOf(JudgeEvaluationException.class).hasMessageContaining("Correctness")
                .hasCauseInstanceOf(RateLimitException.class);
    }

    @Test
    void anOverLongOutputIsRefusedNamingTheLimitNotCut() {
        ScriptedClassifier c = ScriptedClassifier.builder().yesNo("criterion", 0.9).build();

        assertThatThrownBy(() -> judged(c).build().matches("x".repeat(ClassifyRequest.MAX_STATE_CHARS + 1))).isInstanceOf(JudgeEvaluationException.class)
                .hasRootCauseMessage("the state is " + (ClassifyRequest.MAX_STATE_CHARS + 1) + " characters, over the limit of " + ClassifyRequest.MAX_STATE_CHARS
                        + "; shorten it");
    }

    // ---- configuration

    @Test
    void aJudgeAndAClassifierTogetherIsAMistake() {
        assertThatThrownBy(() -> judged(ScriptedClassifier.builder().yesNo("criterion", 0.9).build()).judge(FakeJudge.rating(3)).build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("either");
    }

    @Test
    void neitherIsStillTheOldError() {
        assertThatThrownBy(() -> llmJudged("X").criteria("c").build()).isInstanceOf(NullPointerException.class).hasMessageContaining("judge cannot be null");
    }

    @Test
    void theChatJudgesFailureMessageIsExactlyWhatItWas() {
        LLMClient chat = new io.github.llm4j.eval.testing.ScriptedClient().otherwise("```json\n{\"reasoning\": \"it says 14 days\", \"rating\": 2}\n```");
        LlmJudgeCondition cond = llmJudged("Correctness").criteria("states 30 days").judge(chat).threshold(0.7).build();

        cond.matches("14 days");

        assertThat(cond.description().value()).isEqualTo("llm-judged \"Correctness\" (score=0.25, threshold=0.70): it says 14 days");
    }
}
