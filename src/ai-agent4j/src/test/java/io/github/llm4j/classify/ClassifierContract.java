package io.github.llm4j.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * One behavioural contract every {@link Classifier} backend meets (A1, A2, A4): the answers are
 * exactly the questions asked, with values the request allows, and anything else is a {@link
 * ClassificationException}, never a value. A backend test extends this and says how its wire
 * carries the neutral {@link A} answers.
 */
public abstract class ClassifierContract {

    /** A neutral description of one answer, which each backend renders in its own wire format. */
    public record A(String name, Kind kind, String label, double number, Double confidence) {
        public enum Kind {
            CHOICE,
            YES_NO,
            SCORE
        }

        public static A choice(String name, String label, Double confidence) {
            return new A(name, Kind.CHOICE, label, 0, confidence);
        }

        public static A yesNo(String name, double probability) {
            return new A(name, Kind.YES_NO, null, probability, null);
        }

        public static A score(String name, String point, Double confidence) {
            return new A(name, Kind.SCORE, point, 0, confidence);
        }
    }

    /** A classifier whose backend answers its next call with exactly these answers. */
    protected abstract Classifier answering(List<A> answers);

    /** How many calls the last classifier built by {@link #answering} sent to its backend. */
    protected abstract int callsSent();

    /** Whether this backend's answers are calibrated (Jev) or not (a chat model). */
    protected abstract boolean calibrated();

    protected static ClassifyRequest request() {
        return ClassifyRequest.builder()
                .state("message", "I want my money back, order 8812 arrived broken")
                .choice("intent", "What does the customer want?", "refund", "order_status", "other")
                .yesNo("urgent", "Is the customer threatening to leave?")
                .score("tone", "How hostile is the message?", Scale.of("calm", "annoyed", "angry"))
                .build();
    }

    private static List<A> good() {
        return List.of(
                A.choice("intent", "refund", 0.9),
                A.yesNo("urgent", 0.8),
                A.score("tone", "annoyed", 0.7));
    }

    @Test
    void answersExactlyTheQuestionsAskedWithAllowedValues() {
        ClassifyResult r = answering(good()).classify(request());

        assertThat(r.answers().keySet()).containsExactly("intent", "urgent", "tone");
        assertThat(r.choice("intent").label()).isEqualTo("refund");
        assertThat(r.yesNo("urgent").probability()).isBetween(0.0, 1.0);
        assertThat(r.yesNo("urgent").yes()).isTrue();
        assertThat(r.score("tone").nearest()).isEqualTo("annoyed");
        assertThat(r.score("tone").position()).isBetween(0.0, 1.0);
        assertThat(callsSent()).isEqualTo(1);
    }

    @Test
    void usageNamesTheBackend() {
        ClassifyUsage u = answering(good()).classify(request()).usage();

        assertThat(u.backend()).isNotBlank();
        assertThat(u.latencyMillis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void calibratedOnlyWhenTheBackendIs() {
        ClassifyResult r = answering(good()).classify(request());

        r.answers().values().forEach(a -> assertThat(a.calibrated()).isEqualTo(calibrated()));
    }

    @Test
    void aLabelThatWasNotOfferedIsAnErrorNotAValue() {
        Classifier c =
                answering(
                        List.of(
                                A.choice("intent", "refunds", 0.9),
                                A.yesNo("urgent", 0.8),
                                A.score("tone", "annoyed", 0.7)));

        assertThatThrownBy(() -> c.classify(request()))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("refunds");
    }

    @Test
    void aScalePointThatWasNotOfferedIsAnError() {
        Classifier c =
                answering(
                        List.of(
                                A.choice("intent", "refund", 0.9),
                                A.yesNo("urgent", 0.8),
                                A.score("tone", "furious", 0.7)));

        assertThatThrownBy(() -> c.classify(request()))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("furious");
    }

    @Test
    void aMissingQuestionIsAnError() {
        Classifier c =
                answering(List.of(A.choice("intent", "refund", 0.9), A.yesNo("urgent", 0.8)));

        assertThatThrownBy(() -> c.classify(request()))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("tone");
    }

    @Test
    void anExtraQuestionIsAnError() {
        Classifier c =
                answering(
                        List.of(
                                A.choice("intent", "refund", 0.9),
                                A.yesNo("urgent", 0.8),
                                A.score("tone", "annoyed", 0.7),
                                A.yesNo("spam", 0.1)));

        assertThatThrownBy(() -> c.classify(request()))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("spam");
    }

    @Test
    void aProbabilityOutsideZeroToOneIsAnError() {
        for (double bad : new double[] {1.2, -0.1}) {
            Classifier c =
                    answering(
                            List.of(
                                    A.choice("intent", "refund", 0.9),
                                    A.yesNo("urgent", bad),
                                    A.score("tone", "annoyed", 0.7)));

            assertThatThrownBy(() -> c.classify(request()))
                    .as("probability " + bad)
                    .isInstanceOf(ClassificationException.class);
        }
    }

    @Test
    void aConfidenceOutsideZeroToOneIsAnError() {
        Classifier c =
                answering(
                        List.of(
                                A.choice("intent", "refund", 1.7),
                                A.yesNo("urgent", 0.8),
                                A.score("tone", "annoyed", 0.7)));

        assertThatThrownBy(() -> c.classify(request())).isInstanceOf(ClassificationException.class);
    }

    @Test
    void anAnswerOfTheWrongKindIsAnError() {
        Classifier c =
                answering(
                        List.of(
                                A.yesNo("intent", 0.5),
                                A.yesNo("urgent", 0.8),
                                A.score("tone", "annoyed", 0.7)));

        assertThatThrownBy(() -> c.classify(request())).isInstanceOf(ClassificationException.class);
    }
}
