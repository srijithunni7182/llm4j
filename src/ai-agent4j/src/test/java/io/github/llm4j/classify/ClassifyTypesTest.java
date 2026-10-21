package io.github.llm4j.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.provider.ClassificationProvider;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/**
 * The value types and the checks every backend's result passes (R1, R2, R6's "no request when
 * invalid").
 */
class ClassifyTypesTest {

    @Test
    void aRequestNeedsAStateAndQuestions() {
        assertThatThrownBy(() -> ClassifyRequest.builder().yesNo("a", "b").build())
                .hasMessageContaining("state");
        assertThatThrownBy(() -> ClassifyRequest.builder().state("x").build())
                .hasMessageContaining("question");
    }

    @Test
    void questionNamesAreUnique() {
        assertThatThrownBy(
                        () ->
                                ClassifyRequest.builder()
                                        .state("x")
                                        .yesNo("a", "one?")
                                        .yesNo("a", "two?")
                                        .build())
                .hasMessageContaining("duplicate question name: a");
    }

    @Test
    void aChoiceNeedsTwoDistinctLabels() {
        assertThatThrownBy(() -> Question.Choice.of("k", "kind?", "only"))
                .hasMessageContaining("at least two");
        assertThatThrownBy(() -> Question.Choice.of("k", "kind?", "a", "a"))
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> Question.Choice.of("k", "kind?", "a", " "))
                .hasMessageContaining("label");
    }

    @Test
    void aScaleHasTwoToTenUniquePoints() {
        assertThatThrownBy(() -> Scale.of("only")).hasMessageContaining("2 to 10");
        assertThatThrownBy(() -> Scale.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"))
                .hasMessageContaining("2 to 10");
        assertThatThrownBy(() -> Scale.of("a", "a")).hasMessageContaining("unique");
        assertThat(Scale.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10").size())
                .isEqualTo(10);
    }

    @Test
    void positionsRunFromZeroToOneAndNearestRoundsBack() {
        Scale s = Scale.of("calm", "annoyed", "angry");

        assertThat(s.positionOf("calm")).isEqualTo(0.0);
        assertThat(s.positionOf("annoyed")).isEqualTo(0.5);
        assertThat(s.positionOf("angry")).isEqualTo(1.0);
        assertThat(s.nearest(0.76)).isEqualTo("angry");
        assertThat(s.nearest(0.66)).isEqualTo("annoyed");
        assertThat(s.nearest(0.24)).isEqualTo("calm");
        assertThat(s.nearest(2.0)).isEqualTo("angry");
    }

    @Test
    void namesAreRestrictedSoTheyAreSafeAsKeysAndMarkers() {
        assertThatThrownBy(() -> new Question.YesNo("has space", "x?"))
                .hasMessageContaining("name");
        assertThatThrownBy(
                        () ->
                                ClassifyRequest.builder()
                                        .state("bad name", "x")
                                        .yesNo("a", "b?")
                                        .build())
                .hasMessageContaining("state field name");
        assertThatThrownBy(() -> new Question.YesNo("a", " ")).hasMessageContaining("instructions");
    }

    @Test
    void anUnnamedTextMustBeTheOnlyField() {
        assertThatThrownBy(
                        () ->
                                ClassifyRequest.builder()
                                        .state("text")
                                        .state("other", "x")
                                        .yesNo("a", "b?")
                                        .build())
                .hasMessageContaining("only field");
        assertThat(ClassifyRequest.builder().state("text").yesNo("a", "b?").build().plainText())
                .contains("text");
    }

    @Test
    void anOverLongStateIsRejectedNamingTheLimitNotCut() {
        String huge = "x".repeat(ClassifyRequest.MAX_STATE_CHARS + 1);

        assertThatThrownBy(() -> ClassifyRequest.builder().state(huge).yesNo("a", "b?").build())
                .hasMessageContaining(String.valueOf(ClassifyRequest.MAX_STATE_CHARS));
    }

    @Test
    void answersRefuseValuesOutsideTheirRange() {
        assertThatThrownBy(() -> new YesNoAnswer(1.2, false)).hasMessageContaining("0 to 1");
        assertThatThrownBy(() -> new YesNoAnswer(Double.NaN, false)).hasMessageContaining("0 to 1");
        assertThatThrownBy(
                        () ->
                                new ChoiceAnswer(
                                        "a", Map.of("a", -0.1), OptionalDouble.empty(), false))
                .hasMessageContaining("probability");
        assertThatThrownBy(() -> new ChoiceAnswer("a", Map.of(), OptionalDouble.of(2), false))
                .hasMessageContaining("confidence");
        assertThatThrownBy(() -> new ScoreAnswer(1.5, "x", Map.of(), OptionalDouble.empty(), false))
                .hasMessageContaining("position");
    }

    @Test
    void yesNoCertaintyIsDistanceFromACoinToss() {
        assertThat(new YesNoAnswer(0.9, true).certainty()).isEqualTo(0.9);
        assertThat(new YesNoAnswer(0.1, true).certainty()).isEqualTo(0.9);
        assertThat(new YesNoAnswer(0.5, true).certainty()).isEqualTo(0.5);
        assertThat(new YesNoAnswer(0.1, true).yes()).isFalse();
    }

    @Test
    void rationaleIsEmptyUnlessGiven() {
        assertThat(new YesNoAnswer(0.9, false).rationale()).isEmpty();
        assertThat(new YesNoAnswer(0.9, false, "because").rationale()).contains("because");
    }

    @Test
    void resultAccessorsAreTypedAndExplainTheirFailures() {
        ClassifyResult r =
                new ClassifyResult(
                        Map.of("k", new ChoiceAnswer("a", Map.of(), OptionalDouble.empty(), false)),
                        new ClassifyUsage(1, 2, 3, "x", "m"));

        assertThat(r.choice("k").label()).isEqualTo("a");
        assertThatThrownBy(() -> r.yesNo("k")).hasMessageContaining("not a yes/no");
        assertThatThrownBy(() -> r.score("k")).hasMessageContaining("not a score");
        assertThatThrownBy(() -> r.choice("nope")).hasMessageContaining("answered: [k]");
    }

    @Test
    void usageAddsUp() {
        ClassifyUsage a = new ClassifyUsage(10, 5, 100, "llm", "m1");
        ClassifyUsage b = new ClassifyUsage(20, 6, 50, "llm", "");

        assertThat(a.plus(b)).isEqualTo(new ClassifyUsage(30, 11, 150, "llm", "m1"));
        assertThatThrownBy(() -> new ClassifyUsage(-1, 0, 0, "x", "y"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- what Classifier.using(provider) checks in every result, whatever the provider does (R1,
    // R2)

    private static final ClassifyRequest REQ =
            ClassifyRequest.builder()
                    .state("hello")
                    .choice("kind", "kind?", "a", "b")
                    .yesNo("flag", "flag?")
                    .build();

    private static Classifier providerReturning(Map<String, Answer> answers) {
        ClassificationProvider p =
                new ClassificationProvider() {
                    @Override
                    public ClassifyResult classify(ClassifyRequest request) {
                        return new ClassifyResult(answers, new ClassifyUsage(1, 1, 1, "t", "m"));
                    }

                    @Override
                    public String getProviderName() {
                        return "t";
                    }

                    @Override
                    public void validate() {}
                };
        return Classifier.using(p);
    }

    private static ChoiceAnswer choice(String label) {
        return new ChoiceAnswer(label, Map.of(), OptionalDouble.empty(), false);
    }

    @Test
    void aProviderResultIsCheckedAgainstTheRequest() {
        assertThat(
                        providerReturning(
                                        Map.of(
                                                "kind",
                                                choice("a"),
                                                "flag",
                                                new YesNoAnswer(0.2, true)))
                                .classify(REQ)
                                .choice("kind")
                                .label())
                .isEqualTo("a");
        assertThatThrownBy(
                        () ->
                                providerReturning(
                                                Map.of(
                                                        "kind",
                                                        choice("c"),
                                                        "flag",
                                                        new YesNoAnswer(0.2, true)))
                                        .classify(REQ))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("'c'");
        assertThatThrownBy(() -> providerReturning(Map.of("kind", choice("a"))).classify(REQ))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("missing [flag]");
        assertThatThrownBy(
                        () ->
                                providerReturning(Map.of("kind", choice("a"), "flag", choice("a")))
                                        .classify(REQ))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("yes/no");
        assertThatThrownBy(
                        () ->
                                providerReturning(
                                                Map.of(
                                                        "kind",
                                                        new ChoiceAnswer(
                                                                "a",
                                                                Map.of("z", 0.5),
                                                                OptionalDouble.empty(),
                                                                false),
                                                        "flag",
                                                        new YesNoAnswer(0.2, true)))
                                        .classify(REQ))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("unknown label 'z'");
    }

    @Test
    void aProviderIsValidatedWhenWrapped() {
        ClassificationProvider broken =
                new ClassificationProvider() {
                    @Override
                    public ClassifyResult classify(ClassifyRequest request) {
                        return null;
                    }

                    @Override
                    public String getProviderName() {
                        return "t";
                    }

                    @Override
                    public void validate() {
                        throw new IllegalStateException("no key");
                    }
                };

        assertThatThrownBy(() -> Classifier.using(broken)).hasMessage("no key");
    }

    @Test
    void aNullResultIsAnError() {
        ClassificationProvider none =
                new ClassificationProvider() {
                    @Override
                    public ClassifyResult classify(ClassifyRequest request) {
                        return null;
                    }

                    @Override
                    public String getProviderName() {
                        return "t";
                    }

                    @Override
                    public void validate() {}
                };

        assertThatThrownBy(() -> Classifier.using(none).classify(REQ))
                .isInstanceOf(ClassificationException.class);
    }

    // ---- observation (R12): numbers and names, never the state or a reason

    @Test
    void anObserverSeesNumbersAndLabelsButNeverTheStateOrTheReason() {
        List<ClassifyEvent> seen = new java.util.ArrayList<>();
        Classifier c =
                providerReturning(
                                Map.of(
                                        "kind",
                                        new ChoiceAnswer(
                                                "a",
                                                Map.of(),
                                                OptionalDouble.empty(),
                                                false,
                                                "secret reason"),
                                        "flag",
                                        new YesNoAnswer(0.2, true)))
                        .observed(seen::add);

        c.classify(REQ);

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).outcome()).isEqualTo(ClassifyEvent.Outcome.OK);
        assertThat(seen.get(0).questions()).isEqualTo(2);
        assertThat(seen.get(0).labels()).isEqualTo(Map.of("kind", "a"));
        assertThat(seen.get(0).toString()).doesNotContain("hello").doesNotContain("secret reason");
    }

    @Test
    void anObserverSeesAFailureAndTheCallStillFails() {
        List<ClassifyEvent> seen = new java.util.ArrayList<>();
        Classifier c =
                providerReturning(Map.of("kind", choice("zzz"), "flag", new YesNoAnswer(0.2, true)))
                        .observed(seen::add);

        assertThatThrownBy(() -> c.classify(REQ)).isInstanceOf(ClassificationException.class);

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).outcome()).isEqualTo(ClassifyEvent.Outcome.FAILED);
        assertThat(seen.get(0).errorType()).isEqualTo("ClassificationException");
        assertThat(seen.get(0).toString()).doesNotContain("hello");
    }

    @Test
    void aBrokenObserverNeverChangesTheOutcome() {
        Classifier c =
                providerReturning(Map.of("kind", choice("a"), "flag", new YesNoAnswer(0.2, true)))
                        .observed(
                                e -> {
                                    throw new IllegalStateException("boom");
                                });

        assertThat(c.classify(REQ).choice("kind").label()).isEqualTo("a");
    }

    @Test
    void theBuilderTakesEveryQuestionKindAndAMapOfFields() {
        ClassifyRequest r =
                ClassifyRequest.builder()
                        .state(Map.of("m", "hi"))
                        .choice("k", "k?", List.of(new Option("a", "first"), Option.of("b")))
                        .score("s", "s?", Scale.of("lo", "hi"))
                        .question(new Question.YesNo("y", "y?"))
                        .build();

        assertThat(r.questions()).hasSize(3);
        assertThat(r.question("k")).isPresent();
        assertThat(r.question("nope")).isEmpty();
        assertThat(r.plainText()).isEmpty();
        assertThat(((Question.Choice) r.questions().get(0)).options().get(0).description())
                .isEqualTo("first");
    }

    @Test
    void nullAndBlankPiecesAreRefused() {
        assertThatThrownBy(() -> new Option(" ", "x")).hasMessageContaining("label");
        assertThat(new Option("a", null).description()).isEmpty();
        assertThatThrownBy(() -> new Question.Score("s", "s?", null)).hasMessageContaining("scale");
        assertThatThrownBy(
                        () -> ClassifyRequest.builder().state("m", null).yesNo("y", "y?").build())
                .hasMessageContaining("null");
        assertThatThrownBy(() -> new ClassifyResult(Map.of(), null)).hasMessageContaining("usage");
        assertThatThrownBy(() -> new ChoiceAnswer(" ", Map.of(), OptionalDouble.empty(), false))
                .hasMessageContaining("label");
        assertThatThrownBy(() -> new ScoreAnswer(0.5, " ", Map.of(), OptionalDouble.empty(), false))
                .hasMessageContaining("nearest");
        assertThatThrownBy(() -> Scale.of("a", "b").positionOf("c"))
                .hasMessageContaining("not a point");
        assertThatThrownBy(() -> new Scale(null)).hasMessageContaining("2 to 10");
        assertThatThrownBy(() -> Scale.of("a", " ")).hasMessageContaining("blank");
    }

    @Test
    void aScoreAnswerChecksItsPointsAndKeepsItsReason() {
        ScoreAnswer a =
                new ScoreAnswer(
                        0.5, "mid", Map.of("mid", 0.7), OptionalDouble.of(0.7), false, "because");

        assertThat(a.rationale()).contains("because");
        assertThat(new ScoreAnswer(0.5, "mid", null, null, false).probabilities()).isEmpty();
        assertThat(new ScoreAnswer(0.5, "mid", null, null, false).rationale()).isEmpty();
        assertThat(new ChoiceAnswer("a", null, null, false, "why").rationale()).contains("why");
    }

    @Test
    void aProviderResultWithAnUnknownScalePointIsRefused() {
        ClassifyRequest req =
                ClassifyRequest.builder().state("t").score("s", "s?", Scale.of("lo", "hi")).build();
        ClassificationProvider p =
                new ClassificationProvider() {
                    @Override
                    public ClassifyResult classify(ClassifyRequest request) {
                        return new ClassifyResult(
                                Map.of(
                                        "s",
                                        new ScoreAnswer(
                                                0.5,
                                                "mid",
                                                Map.of(),
                                                OptionalDouble.empty(),
                                                true)),
                                new ClassifyUsage(0, 0, 0, "t", "m"));
                    }

                    @Override
                    public String getProviderName() {
                        return "t";
                    }

                    @Override
                    public void validate() {}
                };
        ClassificationProvider q =
                new ClassificationProvider() {
                    @Override
                    public ClassifyResult classify(ClassifyRequest request) {
                        return new ClassifyResult(
                                Map.of(
                                        "s",
                                        new ScoreAnswer(
                                                0.5,
                                                "lo",
                                                Map.of("zzz", 0.5),
                                                OptionalDouble.empty(),
                                                true)),
                                new ClassifyUsage(0, 0, 0, "t", "m"));
                    }

                    @Override
                    public String getProviderName() {
                        return "t";
                    }

                    @Override
                    public void validate() {}
                };

        assertThatThrownBy(() -> Classifier.using(p).classify(req))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("'mid'");
        assertThatThrownBy(() -> Classifier.using(q).classify(req))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("unknown point");
        assertThatThrownBy(() -> Classifier.using(p).classify(null))
                .isInstanceOf(NullPointerException.class);
    }
}
