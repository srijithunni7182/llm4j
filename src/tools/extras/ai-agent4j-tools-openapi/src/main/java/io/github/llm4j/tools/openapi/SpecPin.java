package io.github.llm4j.tools.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * The pin of an operation: a short hash of its signature, written into a script by {@code weave
 * connect} so that a later change to the description is noticed (phase 36, D4 and D12).
 *
 * <p>The pin covers what a caller can feel: the method, the path, each parameter (name, location,
 * whether it is required, its schema) and the request body (its type, whether it is required, its
 * schema). It does not cover text that only describes ({@code description}, {@code summary}, {@code
 * title}, {@code example}), the order of parameters or of keys, or any operation nobody uses, so a
 * reworded description or a new operation raises no alarm. A pin proves the signature is the one
 * that was reviewed; it says nothing about what the server does.
 */
public final class SpecPin {

    /** The prefix that says which hash a pin is. */
    public static final String PREFIX = "sha256-";

    private static final int HEX_CHARS = 16;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Keys that describe and do not constrain; they never change a pin. */
    private static final Set<String> DESCRIPTIVE =
            Set.of(
                    "description",
                    "summary",
                    "title",
                    "example",
                    "examples",
                    "externalDocs",
                    "xml",
                    "deprecated");

    private SpecPin() {}

    /** The pin of one operation. */
    public static String of(ApiOperation op) {
        ObjectNode signature = JSON.createObjectNode();
        signature.put("method", op.method());
        signature.put("path", op.path());
        List<ApiOperation.Parameter> sorted = new ArrayList<>(op.parameters());
        sorted.sort(
                Comparator.comparing(ApiOperation.Parameter::location)
                        .thenComparing(ApiOperation.Parameter::name));
        ArrayNode parameters = signature.putArray("parameters");
        for (ApiOperation.Parameter p : sorted) {
            ObjectNode node = parameters.addObject();
            node.put("name", p.name());
            node.put("in", p.location());
            node.put("required", p.required());
            node.set("schema", canonical(p.schema()));
        }
        if (op.body() != null) {
            ObjectNode body = signature.putObject("body");
            body.put("required", op.body().required());
            body.put("contentType", op.body().contentType());
            body.set("schema", canonical(op.body().schema()));
        }
        return PREFIX + hash(write(canonical(signature)));
    }

    /** The node with descriptive keys removed and every object's keys in sorted order. */
    static JsonNode canonical(JsonNode node) {
        if (node == null || node.isNull()) return JSON.nullNode();
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
                String name = names.next();
                if (!DESCRIPTIVE.contains(name)) sorted.put(name, canonical(node.get(name)));
            }
            ObjectNode out = JSON.createObjectNode();
            sorted.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            node.forEach(item -> out.add(canonical(item)));
            return out;
        }
        return node;
    }

    private static String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("a signature could not be written", e);
        }
    }

    private static String hash(String text) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, HEX_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
