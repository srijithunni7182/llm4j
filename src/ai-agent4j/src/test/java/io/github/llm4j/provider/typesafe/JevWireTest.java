package io.github.llm4j.provider.typesafe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.classify.ChoiceAnswer;
import io.github.llm4j.classify.ClassificationException;
import io.github.llm4j.classify.ClassifyRequest;
import io.github.llm4j.classify.ClassifyResult;
import io.github.llm4j.classify.Option;
import io.github.llm4j.classify.Scale;
import io.github.llm4j.classify.ScoreAnswer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link JevWire} against the requests we author and the replies the real API gave (recorded by
 * scripts/record-jev-fixtures.py).
 */
class JevWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static String fixture(String name) {
        try (var in = JevWireTest.class.getResourceAsStream("/jev/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("missing fixture " + name, e);
        }
    }

    static ClassifyRequest choiceRequest() {
        return ClassifyRequest.builder()
                .state("I want my money back for order 8812, the item arrived broken")
                .choice(
                        "intent",
                        "What does the customer want?",
                        List.of(
                                new Option(
                                        "refund",
                                        "The customer wants money back or a return/refund processed"),
                                new Option(
                                        "order_status",
                                        "The customer asks where an order is or when it will arrive"),
                                Option.of("other")))
                .build();
    }

    static ClassifyRequest yesNoRequest() {
        return ClassifyRequest.builder()
                .state("ls -la")
                .yesNo(
                        "destructive",
                        "Would running this shell command be destructive, irreversible, or exfiltrate data?")
                .build();
    }

    static ClassifyRequest scoreRequest() {
        return ClassifyRequest.builder()
                .state("Your support team has been rude and useless")
                .score("tone", "How hostile is the message?", Scale.of("calm", "annoyed", "angry"))
                .build();
    }

    static ClassifyRequest multiRequest() {
        return ClassifyRequest.builder()
                .state(
                        "message",
                        "Our API integration started returning 500 errors on every request about 20 minutes ago, and we can't process any customer orders until this is fixed.")
                .state("history", "Customer wrote in twice yesterday about the same integration.")
                .choice(
                        "department",
                        "Which team should handle this?",
                        List.of(
                                new Option("billing", "Payment or subscription issues"),
                                new Option("technical", "Bugs or integration problems"),
                                new Option("sales", "Pricing or account questions")))
                .yesNo("urgent", "Is the customer threatening to leave?")
                .score(
                        "frustration",
                        "How frustrated does the customer sound?",
                        Scale.of("calm", "frustrated but civil", "very angry"))
                .build();
    }

    private static void assertBody(ClassifyRequest request, String fixtureName) throws IOException {
        JsonNode sent = JSON.readTree(JevWire.requestBody(request, "jev-latest"));
        assertThat(sent)
                .as("request body for " + fixtureName)
                .isEqualTo(JSON.readTree(fixture(fixtureName)));
    }

    // ---- our request, as Jev wants it

    @Test
    void aChoiceIsSentAsACriteriaMapWithLabelsAsDescriptionsWhenThereAreNone() throws IOException {
        assertBody(choiceRequest(), "choice.request.json");
    }

    @Test
    void aYesNoIsANoulQuestion() throws IOException {
        assertBody(yesNoRequest(), "yesno.request.json");
    }

    @Test
    void aScoreIsAnOrderedCriteriaArray() throws IOException {
        assertBody(scoreRequest(), "score.request.json");
    }

    @Test
    void namedFieldsAreAnObjectAndAPlainTextIsAString() throws IOException {
        assertBody(multiRequest(), "multi.request.json");
        assertThat(JSON.readTree(JevWire.requestBody(yesNoRequest(), "m")).get("state").isTextual())
                .isTrue();
        assertThat(JSON.readTree(JevWire.requestBody(multiRequest(), "m")).get("state").isObject())
                .isTrue();
    }

    // ---- Jev's replies, as our answers

    private static ClassifyResult parse(String reply, ClassifyRequest request) {
        return JevWire.parse(reply, request, 123);
    }

    @Test
    void aRecordedChoiceReplyBecomesACalibratedChoiceAnswer() {
        ClassifyResult r = parse(fixture("choice.response.json"), choiceRequest());

        ChoiceAnswer a = r.choice("intent");
        assertThat(a.label()).isEqualTo("refund");
        assertThat(a.probabilities())
                .containsEntry("refund", 1.0)
                .containsEntry("order_status", 0.0);
        assertThat(a.confidence()).hasValue(1.0);
        assertThat(a.calibrated()).isTrue();
        assertThat(r.usage().inputTokens()).isEqualTo(357);
        assertThat(r.usage().outputTokens()).isEqualTo(39);
        assertThat(r.usage().model()).isEqualTo("jev-1.13.0");
        assertThat(r.usage().backend()).isEqualTo("jev");
        assertThat(r.usage().latencyMillis()).isEqualTo(123);
    }

    @Test
    void aRecordedNoulReplyIsTheProbabilityOfYes() {
        var a = parse(fixture("yesno.response.json"), yesNoRequest()).yesNo("destructive");

        assertThat(a.probability()).isEqualTo(0.01);
        assertThat(a.calibrated()).isTrue();
    }

    @Test
    void aRecordedScoreReplyIsNormalisedOverTheScaleAndKeyedByPointLabel() {
        ScoreAnswer a = parse(fixture("score.response.json"), scoreRequest()).score("tone");

        assertThat(a.position()).isCloseTo(0.965, within(1e-9)); // 1.93 of 0..2
        assertThat(a.nearest()).isEqualTo("angry");
        assertThat(a.probabilities())
                .containsEntry("angry", 0.93)
                .containsEntry("annoyed", 0.07)
                .containsEntry("calm", 0.0);
        assertThat(a.confidence()).hasValue(0.9);
    }

    @Test
    void aRecordedMultiQuestionReplyAnswersAllThree() {
        ClassifyResult r = parse(fixture("multi.response.json"), multiRequest());

        assertThat(r.choice("department").label()).isEqualTo("technical");
        assertThat(r.yesNo("urgent").probability()).isEqualTo(0.06);
        assertThat(r.score("frustration").nearest()).isEqualTo("frustrated but civil");
    }

    // ---- replies that are not answers

    @Test
    void aBodyThatIsNotJsonOrHasNoAnswersIsAClassificationException() {
        assertThatThrownBy(() -> parse("not json", yesNoRequest()))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("JSON");
        assertThatThrownBy(() -> parse("{\"model\": \"jev\"}", yesNoRequest()))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("answers");
        assertThatThrownBy(() -> parse("{\"answers\": {}}", yesNoRequest()))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("destructive");
    }

    @Test
    void anAnswerOfTheWrongShapeIsAClassificationException() {
        String usage = ", \"usage\": {\"input_tokens\": 1, \"output_tokens\": 1}";
        assertThatThrownBy(
                        () ->
                                parse(
                                        "{\"answers\": {\"destructive\": {\"type\": \"choice\", \"choice\": \"x\"}}"
                                                + usage
                                                + "}",
                                        yesNoRequest()))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("noul");
        assertThatThrownBy(
                        () ->
                                parse(
                                        "{\"answers\": {\"destructive\": {\"type\": \"noul\"}}"
                                                + usage
                                                + "}",
                                        yesNoRequest()))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("noul");
        assertThatThrownBy(
                        () ->
                                parse(
                                        "{\"answers\": {\"destructive\": {\"type\": \"noul\", \"noul\": \"high\"}}"
                                                + usage
                                                + "}",
                                        yesNoRequest()))
                .isInstanceOf(ClassificationException.class);
        assertThatThrownBy(
                        () ->
                                parse(
                                        "{\"answers\": {\"tone\": {\"type\": \"score\", \"score\": 5.0, \"confidence\": 0.5}}"
                                                + usage
                                                + "}",
                                        scoreRequest()))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("score");
        assertThatThrownBy(
                        () ->
                                parse(
                                        "{\"answers\": {\"tone\": {\"type\": \"score\", \"confidence\": 0.5}}"
                                                + usage
                                                + "}",
                                        scoreRequest()))
                .isInstanceOf(ClassificationException.class);
        assertThatThrownBy(
                        () ->
                                parse(
                                        "{\"answers\": {\"intent\": {\"type\": \"choice\", \"confidence\": 1.0}}"
                                                + usage
                                                + "}",
                                        choiceRequest()))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("choice");
    }

    @Test
    void anOutOfRangeProbabilityIsAClassificationException() {
        String bad =
                "{\"model\": \"j\", \"answers\": {\"destructive\": {\"type\": \"noul\", \"noul\": 1.4}}, \"usage\": {\"input_tokens\": 1, \"output_tokens\": 1}}";

        assertThatThrownBy(() -> parse(bad, yesNoRequest()))
                .isInstanceOf(ClassificationException.class)
                .hasMessageContaining("0 to 1");
    }

    @Test
    void missingUsageOrModelIsTolerated() {
        ClassifyResult r =
                parse(
                        "{\"answers\": {\"destructive\": {\"type\": \"noul\", \"noul\": 0.5}}}",
                        yesNoRequest());

        assertThat(r.usage().inputTokens()).isZero();
        assertThat(r.usage().model()).isEmpty();
    }

    @Test
    void aValidationErrorReadsTheLocationAndMessageButNeverEchoesTheInput() {
        String msg = JevWire.describeValidationError(fixture("invalid_422.response.json"));

        assertThat(msg).contains("questions.k.choice.criteria").contains("at least 1 item");
        assertThat(JevWire.describeValidationError("not json"))
                .isEqualTo("the request was rejected");
        assertThat(JevWire.describeValidationError("{\"detail\": \"just text\"}"))
                .contains("just text");
    }
}
