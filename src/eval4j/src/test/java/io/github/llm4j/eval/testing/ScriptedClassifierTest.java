package io.github.llm4j.eval.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.classify.ClassificationException;
import io.github.llm4j.classify.ClassifyRequest;
import io.github.llm4j.classify.ClassifyUsage;
import io.github.llm4j.classify.Scale;
import io.github.llm4j.exception.RateLimitException;
import org.junit.jupiter.api.Test;

class ScriptedClassifierTest {

    private static final ClassifyRequest REQ =
            ClassifyRequest.builder()
                    .state("m")
                    .choice("intent", "what?", "refund", "other")
                    .yesNo("urgent", "urgent?")
                    .score("tone", "how?", Scale.of("calm", "annoyed", "angry"))
                    .build();

    @Test
    void answersEachQuestionByName() {
        ScriptedClassifier c =
                ScriptedClassifier.builder()
                        .choice("intent", "refund", 0.93)
                        .yesNo("urgent", 0.12)
                        .score("tone", 0.9)
                        .build();

        var r = c.classify(REQ);

        assertThat(r.choice("intent").label()).isEqualTo("refund");
        assertThat(r.choice("intent").confidence()).hasValue(0.93);
        assertThat(r.yesNo("urgent").probability()).isEqualTo(0.12);
        assertThat(r.score("tone").nearest()).isEqualTo("angry");
        assertThat(r.choice("intent").calibrated()).isFalse();
        assertThat(c.requests()).containsExactly(REQ);
    }

    @Test
    void severalCallsAreScriptedInOrderAndTheLastOneRepeats() {
        ScriptedClassifier c =
                ScriptedClassifier.builder()
                        .choice("intent", "refund")
                        .yesNo("urgent", 0)
                        .score("tone", 0)
                        .next()
                        .choice("intent", "other")
                        .yesNo("urgent", 1)
                        .score("tone", 0)
                        .build();

        assertThat(c.classify(REQ).choice("intent").label()).isEqualTo("refund");
        assertThat(c.classify(REQ).choice("intent").label()).isEqualTo("other");
        assertThat(c.classify(REQ).choice("intent").label()).isEqualTo("other");
        assertThat(c.requests()).hasSize(3);
    }

    @Test
    void aQuestionItWasNotScriptedForIsAnError() {
        ScriptedClassifier c = ScriptedClassifier.builder().choice("intent", "refund").build();

        assertThatThrownBy(() -> c.classify(REQ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("urgent");
    }

    @Test
    void aLabelTheRequestDidNotOfferIsRefusedLikeARealBackend() {
        ScriptedClassifier c =
                ScriptedClassifier.builder()
                        .choice("intent", "refunds")
                        .yesNo("urgent", 0)
                        .score("tone", 0)
                        .build();

        assertThatThrownBy(() -> c.classify(REQ))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("refunds");
    }

    @Test
    void aWrongKindIsRefused() {
        ScriptedClassifier c =
                ScriptedClassifier.builder()
                        .yesNo("intent", 0.5)
                        .yesNo("urgent", 0)
                        .score("tone", 0)
                        .build();
        ScriptedClassifier d =
                ScriptedClassifier.builder()
                        .choice("intent", "refund")
                        .choice("urgent", "refund")
                        .score("tone", 0)
                        .build();
        ScriptedClassifier e =
                ScriptedClassifier.builder()
                        .choice("intent", "refund")
                        .yesNo("urgent", 0)
                        .yesNo("tone", 0)
                        .build();

        assertThatThrownBy(() -> c.classify(REQ))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("a choice");
        assertThatThrownBy(() -> d.classify(REQ))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("a yes/no");
        assertThatThrownBy(() -> e.classify(REQ))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("a score");
    }

    @Test
    void aCallCanFailAndTheNextOneAnswers() {
        ScriptedClassifier c =
                ScriptedClassifier.builder()
                        .failWith(new RateLimitException("slow down"))
                        .next()
                        .choice("intent", "refund")
                        .yesNo("urgent", 0)
                        .score("tone", 0)
                        .build();

        assertThatThrownBy(() -> c.classify(REQ)).isInstanceOf(RateLimitException.class);
        assertThat(c.classify(REQ).choice("intent").label()).isEqualTo("refund");
    }

    @Test
    void calibrationAndUsageCanBeScripted() {
        ScriptedClassifier c =
                ScriptedClassifier.builder()
                        .calibrated(true)
                        .usage(new ClassifyUsage(10, 2, 150, "jev", "jev-1"))
                        .choice("intent", "refund")
                        .yesNo("urgent", 0)
                        .score("tone", 0)
                        .build();

        var r = c.classify(REQ);

        assertThat(r.choice("intent").calibrated()).isTrue();
        assertThat(r.usage().backend()).isEqualTo("jev");
    }
}
