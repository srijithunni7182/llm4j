package io.github.llm4j.provider.typesafe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.classify.Answer;
import io.github.llm4j.classify.ClassificationException;
import io.github.llm4j.classify.ClassifyRequest;
import io.github.llm4j.classify.ClassifyResult;
import io.github.llm4j.provider.DecisionWire;
import java.util.ArrayList;
import java.util.List;

/**
 * All of Jev's wire format, in one class: the request body we send to {@code POST /v1/systemone}
 * and how its reply becomes {@link Answer}s. A change in the vendor's API touches this file and its
 * test, nothing else. It is built to the official reference (docs.typesafe.ai, and the OpenAPI
 * document at api.typesafe.ai/openapi.json) and pinned by requests we author and replies the real
 * API gave, under {@code src/test/resources/jev}.
 *
 * <p>Nothing here ever puts the reply body in a message: a malformed reply is described by what is
 * wrong with it, never by its text, so the state or an echoed credential cannot leak through an
 * exception.
 */
final class JevWire {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Answer types, as Jev names them. */
    private static final String CHOICE = "choice";

    private static final String SCORE = "score";
    private static final String NOUL = "noul";

    private JevWire() {}

    // ---- request and reply: the format Jev shares with Perplexity's Decisions API

    /**
     * The JSON body for a request: {@code model}, {@code state} (a string, or an object of the
     * named fields) and one typed question per name.
     */
    static String requestBody(ClassifyRequest request, String model) {
        return DecisionWire.requestBody(request, model, DecisionWire.JEV);
    }

    /**
     * Reads a reply into a result. Every answer is {@link Answer#calibrated() calibrated}: that is
     * what Jev's probabilities are.
     *
     * @throws ClassificationException if the reply is malformed (see {@link DecisionWire})
     */
    static ClassifyResult parse(String reply, ClassifyRequest request, long latencyMillis) {
        return DecisionWire.parse(reply, request, latencyMillis, DecisionWire.JEV);
    }

    // ---- errors

    /**
     * A one-line description of a 422 body: the location and message of each problem ({@code
     * questions.k.choice.criteria: …}). The offending {@code input} the API echoes is left out,
     * because it can be the caller's state.
     */
    static String describeValidationError(String body) {
        try {
            JsonNode detail = JSON.readTree(body).path("detail");
            if (detail.isTextual()) return detail.asText();
            List<String> problems = new ArrayList<>();
            for (JsonNode d : detail) {
                List<String> loc = new ArrayList<>();
                for (JsonNode part : d.path("loc")) loc.add(part.asText());
                if (!loc.isEmpty() && loc.get(0).equals("body")) loc.remove(0);
                String msg = d.path("msg").asText("invalid");
                problems.add(loc.isEmpty() ? msg : String.join(".", loc) + ": " + msg);
            }
            return problems.isEmpty() ? "the request was rejected" : String.join("; ", problems);
        } catch (JsonProcessingException | RuntimeException e) {
            return "the request was rejected";
        }
    }

    /**
     * The 422 body without what it echoed: only {@code type}, {@code loc} and {@code msg} of each
     * problem, so it is safe to keep on an exception.
     */
    static String sanitizeValidationBody(String body) {
        try {
            ArrayNode kept = JSON.createArrayNode();
            for (JsonNode d : JSON.readTree(body).path("detail")) {
                ObjectNode n = kept.addObject();
                n.set("type", d.path("type"));
                n.set("loc", d.path("loc"));
                n.set("msg", d.path("msg"));
            }
            return JSON.createObjectNode().set("detail", kept).toString();
        } catch (JsonProcessingException | RuntimeException e) {
            return "";
        }
    }
}
