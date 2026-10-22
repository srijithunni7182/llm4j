package io.github.llm4j.classify;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.classify.fixtures.ScriptedChat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * For random requests and random replies, valid and invalid: the result either satisfies R1 and R2
 * or is a {@link ClassificationException}. Never both, never neither (never some other exception,
 * never a value that breaks the request's rules).
 */
class LlmClassifierPropertyTest {

    private static final String[] BAD_LABELS = {"zzz", "", "REFUND!", "null", "a b"};

    @Test
    void everyReplyIsEitherAValidResultOrAClassificationException() {
        Random rnd = new Random(20261021L);
        int valid = 0, rejected = 0;
        for (int i = 0; i < 1500; i++) {
            ClassifyRequest request = randomRequest(rnd);
            String reply = randomReply(rnd, request);
            ScriptedChat chat = new ScriptedChat().reply(reply);
            try {
                ClassifyResult result = LlmClassifier.builder(chat).build().classify(request);
                assertThat(satisfies(request, result)).as("reply: %s", reply).isTrue();
                valid++;
            } catch (ClassificationException e) {
                rejected++;
            }
        }
        assertThat(valid).as("the generator produces valid replies").isGreaterThan(200);
        assertThat(rejected).as("and invalid ones").isGreaterThan(200);
    }

    private static ClassifyRequest randomRequest(Random rnd) {
        ClassifyRequest.Builder b = ClassifyRequest.builder().state("t" + rnd.nextInt(1000));
        int n = 1 + rnd.nextInt(4);
        for (int q = 0; q < n; q++) {
            switch (rnd.nextInt(3)) {
                case 0 -> b.choice("q" + q, "pick?", "a" + q, "b" + q, "c" + q);
                case 1 -> b.yesNo("q" + q, "yes?");
                default -> b.score("q" + q, "how?", Scale.of("lo", "mid", "hi"));
            }
        }
        return b.build();
    }

    private static String randomReply(Random rnd, ClassifyRequest request) {
        List<String> parts = new ArrayList<>();
        for (Question q : request.questions()) {
            if (rnd.nextInt(25) == 0) continue; // a missing answer
            String conf =
                    rnd.nextInt(12) == 0
                            ? "1.7"
                            : String.format(Locale.ROOT, "%.2f", rnd.nextDouble());
            String body;
            if (q instanceof Question.Choice c) {
                String label =
                        rnd.nextInt(12) == 0
                                ? BAD_LABELS[rnd.nextInt(BAD_LABELS.length)]
                                : c.labels().get(rnd.nextInt(c.labels().size()));
                body = "{\"label\": \"" + label + "\", \"confidence\": " + conf + "}";
            } else if (q instanceof Question.YesNo) {
                body =
                        rnd.nextInt(12) == 0
                                ? "{\"answer\": \"maybe\"}"
                                : "{\"answer\": \""
                                        + (rnd.nextBoolean() ? "yes" : "no")
                                        + "\", \"confidence\": "
                                        + conf
                                        + "}";
            } else {
                Question.Score s = (Question.Score) q;
                String p =
                        rnd.nextInt(12) == 0
                                ? "extreme"
                                : s.scale().points().get(rnd.nextInt(s.scale().size()));
                body = "{\"point\": \"" + p + "\", \"confidence\": " + conf + "}";
            }
            parts.add("\"" + q.name() + "\": " + body);
        }
        if (rnd.nextInt(25) == 0) parts.add("\"extra\": {\"answer\": \"yes\"}");
        String json = "{\"answers\": {" + String.join(", ", parts) + "}}";
        return switch (rnd.nextInt(30)) {
            case 0 -> json.substring(0, json.length() / 2); // truncated
            case 1 -> "I would say: " + json.substring(0, 5); // prose with a stub
            case 2 -> "```json\n" + json + "\n```"; // fenced: still valid
            case 3 -> json + " Hope this helps!"; // trailing text: still valid
            default -> json;
        };
    }

    /** The oracle, independent of the implementation's checks. */
    private static boolean satisfies(ClassifyRequest request, ClassifyResult result) {
        if (result.answers().size() != request.questions().size()) return false;
        for (Question q : request.questions()) {
            Answer a = result.answers().get(q.name());
            if (a == null || a.calibrated()) return false;
            if (q instanceof Question.Choice c) {
                if (!(a instanceof ChoiceAnswer ca) || !c.labels().contains(ca.label()))
                    return false;
                if (ca.confidence().isPresent()
                        && (ca.confidence().getAsDouble() < 0 || ca.confidence().getAsDouble() > 1))
                    return false;
            } else if (q instanceof Question.YesNo) {
                if (!(a instanceof YesNoAnswer ya)
                        || Double.isNaN(ya.probability())
                        || ya.probability() < 0
                        || ya.probability() > 1) return false;
            } else {
                if (!(a instanceof ScoreAnswer sa)
                        || sa.position() < 0
                        || sa.position() > 1
                        || !((Question.Score) q).scale().points().contains(sa.nearest()))
                    return false;
            }
        }
        return true;
    }
}
