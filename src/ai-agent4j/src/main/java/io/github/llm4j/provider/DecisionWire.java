package io.github.llm4j.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.classify.Answer;
import io.github.llm4j.classify.ChoiceAnswer;
import io.github.llm4j.classify.ClassificationException;
import io.github.llm4j.classify.ClassifyRequest;
import io.github.llm4j.classify.ClassifyResult;
import io.github.llm4j.classify.ClassifyUsage;
import io.github.llm4j.classify.Option;
import io.github.llm4j.classify.Question;
import io.github.llm4j.classify.ScoreAnswer;
import io.github.llm4j.classify.YesNoAnswer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * The wire format shared by the "decision model" APIs, which answer typed questions directly with
 * probabilities and write no text: TypeSafe AI's Jev ({@code POST /v1/systemone}) and Perplexity's
 * Decisions API ({@code POST /v1/decisions}). Both take {@code model}, a {@code state} and named
 * {@code questions} of type {@code noul} (yes/no), {@code choice} or {@code score}, and answer with
 * {@code answers}, {@code probabilities}, a {@code confidence} and {@code usage}. What differs
 * between vendors is the {@link Flavor} (their name in messages, the backend id on usage, and how
 * an option with no description is written); each vendor's endpoint and errors stay in its own
 * provider. Shared plumbing for the provider classes; applications use {@code Classifier}.
 *
 * <p>Nothing here ever puts the reply body in a message: a malformed reply is described by what is
 * wrong with it, never by its text, so the state or an echoed credential cannot leak through an
 * exception.
 */
public final class DecisionWire {

    /** How one vendor's flavour of the shared format differs. */
    public record Flavor(String name, String backend, boolean unnamedOptionsAreNull) {}

    /** TypeSafe AI's Jev: an option without a description is described by its own name. */
    public static final Flavor JEV = new Flavor("Jev", "jev", false);

    /** Perplexity's Decisions API: an option without a description is {@code null}. */
    public static final Flavor PERPLEXITY =
            new Flavor("Perplexity Decisions", "perplexity_decisions", true);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Answer types, as the vendors name them. */
    private static final String CHOICE = "choice";

    private static final String SCORE = "score";
    private static final String NOUL = "noul";

    private DecisionWire() {}

    // ---- request

    /**
     * The JSON body for a request: {@code model}, {@code state} (a string, or an object of the
     * named fields) and one typed question per name.
     */
    public static String requestBody(ClassifyRequest request, String model, Flavor flavor) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", model);
        if (request.plainText().isPresent()) {
            root.put("state", request.plainText().get());
        } else {
            ObjectNode state = root.putObject("state");
            request.state().forEach(state::put);
        }
        ObjectNode questions = root.putObject("questions");
        for (Question q : request.questions()) {
            ObjectNode node = questions.putObject(q.name());
            if (q instanceof Question.Choice c) {
                node.put("type", CHOICE).put("instructions", c.instructions());
                ObjectNode criteria = node.putObject("criteria");
                for (Option o : c.options()) {
                    if (o.description().isEmpty() && flavor.unnamedOptionsAreNull())
                        criteria.putNull(o.label());
                    else
                        criteria.put(
                                o.label(), o.description().isEmpty() ? o.label() : o.description());
                }
            } else if (q instanceof Question.Score s) {
                node.put("type", SCORE).put("instructions", s.instructions());
                ArrayNode criteria = node.putArray("criteria");
                s.scale().points().forEach(criteria::add);
            } else {
                node.put("type", NOUL).put("instructions", q.instructions());
            }
        }
        return root.toString();
    }

    // ---- reply

    /**
     * Reads a reply into a result. Every answer is {@link Answer#calibrated() calibrated}: that is
     * what the probabilities are. The result is not yet checked against the request's labels;
     * {@code Classifier.using(provider)} does that for every provider.
     *
     * @throws ClassificationException if the reply is not JSON, has no answers, answers a question
     *     that was not asked or none that was, or an answer is of the wrong shape or out of range
     */
    public static ClassifyResult parse(
            String reply, ClassifyRequest request, long latencyMillis, Flavor flavor) {
        String who = flavor.name();
        JsonNode root;
        try {
            root = JSON.readTree(reply);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new ClassificationException(who + "'s reply is not JSON");
        }
        JsonNode answers = root == null ? null : root.get("answers");
        if (answers == null || !answers.isObject())
            throw new ClassificationException(who + "'s reply has no \"answers\" object");
        List<String> asked = request.questions().stream().map(Question::name).toList();
        answers.fieldNames()
                .forEachRemaining(
                        name -> {
                            if (!asked.contains(name))
                                throw new ClassificationException(
                                        who
                                                + " answered a question that was not asked: '"
                                                + name
                                                + "'");
                        });
        Map<String, Answer> out = new LinkedHashMap<>();
        for (Question q : request.questions()) {
            JsonNode a = answers.get(q.name());
            if (a == null || !a.isObject())
                throw new ClassificationException(
                        who + " gave no answer for question '" + q.name() + "'");
            try {
                out.put(q.name(), answer(q, a));
            } catch (IllegalArgumentException e) {
                throw new ClassificationException("question '" + q.name() + "': " + e.getMessage());
            }
        }
        JsonNode usage = root.path("usage");
        return new ClassifyResult(
                out,
                new ClassifyUsage(
                        usage.path("input_tokens").asInt(0),
                        usage.path("output_tokens").asInt(0),
                        latencyMillis,
                        flavor.backend(),
                        root.path("model").asText("")));
    }

    private static Answer answer(Question q, JsonNode a) {
        String type = a.path("type").asText("");
        String expected =
                q instanceof Question.Choice ? CHOICE : q instanceof Question.Score ? SCORE : NOUL;
        if (!expected.equals(type))
            throw new ClassificationException(
                    "question '"
                            + q.name()
                            + "': expected a "
                            + expected
                            + " answer, got "
                            + (type.isEmpty() ? "none" : type));
        if (q instanceof Question.Choice) {
            if (!a.path(CHOICE).isTextual())
                throw new ClassificationException(
                        "question '" + q.name() + "': the choice answer has no \"choice\"");
            return new ChoiceAnswer(
                    a.get(CHOICE).asText(), probabilities(q, a, null), confidence(a), true);
        } else if (q instanceof Question.Score s) {
            JsonNode score = a.path(SCORE);
            int last = s.scale().size() - 1;
            if (!score.isNumber() || score.asDouble() < 0 || score.asDouble() > last) {
                throw new ClassificationException(
                        "question '"
                                + q.name()
                                + "': the score "
                                + (score.isNumber() ? score.asText() : "(missing)")
                                + " is not from 0 to "
                                + last);
            }
            double position = score.asDouble() / last;
            return new ScoreAnswer(
                    position,
                    s.scale().nearest(position),
                    probabilities(q, a, s.scale().points()),
                    confidence(a),
                    true);
        }
        if (!a.path(NOUL).isNumber())
            throw new ClassificationException(
                    "question '" + q.name() + "': the noul answer has no number");
        return new YesNoAnswer(a.get(NOUL).asDouble(), true);
    }

    /**
     * The {@code probabilities} object: for a choice keyed by label; for a score keyed by level
     * index, which {@code points} turns into our point labels.
     */
    private static Map<String, Double> probabilities(Question q, JsonNode a, List<String> points) {
        JsonNode node = a.path("probabilities");
        Map<String, Double> out = new LinkedHashMap<>();
        if (!node.isObject()) return out;
        node.fields()
                .forEachRemaining(
                        e -> {
                            if (!e.getValue().isNumber())
                                throw new ClassificationException(
                                        "question '"
                                                + q.name()
                                                + "': a probability is not a number");
                            String key = e.getKey();
                            if (points != null) {
                                int i;
                                try {
                                    i = Integer.parseInt(key);
                                } catch (NumberFormatException nf) {
                                    throw new ClassificationException(
                                            "question '"
                                                    + q.name()
                                                    + "': unexpected score level '"
                                                    + key
                                                    + "'");
                                }
                                if (i < 0 || i >= points.size())
                                    throw new ClassificationException(
                                            "question '"
                                                    + q.name()
                                                    + "': unexpected score level '"
                                                    + key
                                                    + "'");
                                key = points.get(i);
                            }
                            out.put(key, e.getValue().asDouble());
                        });
        return out;
    }

    private static OptionalDouble confidence(JsonNode a) {
        JsonNode c = a.path("confidence");
        return c.isNumber() ? OptionalDouble.of(c.asDouble()) : OptionalDouble.empty();
    }
}
