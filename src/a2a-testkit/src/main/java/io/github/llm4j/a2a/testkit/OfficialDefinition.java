package io.github.llm4j.a2a.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.FieldBehavior;
import com.google.api.FieldBehaviorProto;
import com.google.lf.a2a.v1.A2A;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Validates JSON against the official A2A 1.0.0 protocol definition (the {@code a2a.proto} that this
 * module compiles). It is stricter than protobuf's own JSON parser: names must be the lowerCamel
 * names the specification uses, enums must be strings, fields marked REQUIRED must be present, and a
 * oneof (such as a part's content) must be set. Test tooling only.
 */
public final class OfficialDefinition {

    /** The tag the definition was taken from. */
    public static final String TAG = "v1.0.0";

    /** The SHA-256 the copy of the definition must have. */
    public static final String SHA256 = "4b74c0baa923ae0acb55474e548f1d6e5d3f83b80d757b65f8bf3e99a3c2257f";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> OPAQUE =
            Set.of(
                    "google.protobuf.Struct",
                    "google.protobuf.Value",
                    "google.protobuf.ListValue",
                    "google.protobuf.Timestamp",
                    "google.protobuf.Empty");

    private OfficialDefinition() {}

    /** The SHA-256 of the bundled copy of the definition, in hex. */
    public static String bundledSha256() {
        try (InputStream in = OfficialDefinition.class.getResourceAsStream("/a2a.proto")) {
            if (in == null) {
                throw new IllegalStateException("a2a.proto is not on the classpath");
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Lists what is wrong with a JSON message of the named type; empty means valid.
     *
     * @param messageName the simple name of a message in the definition, such as {@code Task}
     * @param json the JSON text
     */
    public static List<String> problems(String messageName, String json) {
        Descriptor type = A2A.getDescriptor().findMessageTypeByName(messageName);
        if (type == null) {
            throw new IllegalArgumentException("no message " + messageName + " in the official definition");
        }
        List<String> problems = new ArrayList<>();
        JsonNode tree;
        try {
            tree = JSON.readTree(json);
        } catch (IOException e) {
            return List.of("not JSON: " + e.getMessage());
        }
        try {
            JsonFormat.parser().merge(json, DynamicMessage.newBuilder(type));
        } catch (IOException | RuntimeException e) {
            problems.add("protobuf JSON parse: " + e.getMessage());
        }
        walk(type, tree, messageName, problems);
        return problems;
    }

    /**
     * Fails with every problem found.
     *
     * @throws AssertionError if the JSON is not a valid message of that type
     */
    public static void validate(String messageName, String json) {
        List<String> problems = problems(messageName, json);
        if (!problems.isEmpty()) {
            throw new AssertionError(
                    "not a valid " + messageName + " (official definition " + TAG + "): " + problems + "\n" + json);
        }
    }

    private static void walk(Descriptor type, JsonNode node, String path, List<String> problems) {
        if (!node.isObject()) {
            problems.add(path + ": expected an object");
            return;
        }
        for (var it = node.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (type.getFields().stream().noneMatch(f -> f.getJsonName().equals(name))) {
                problems.add(path + "." + name + ": not a field of " + type.getName() + " (names are lowerCamel)");
            }
        }
        for (FieldDescriptor field : type.getFields()) {
            JsonNode value = node.get(field.getJsonName());
            if (value == null) {
                if (isRequired(field)) {
                    problems.add(path + ": missing required field " + field.getJsonName());
                }
                continue;
            }
            String here = path + "." + field.getJsonName();
            if (field.isMapField()) {
                continue;
            }
            if (field.isRepeated()) {
                if (!value.isArray()) {
                    problems.add(here + ": expected an array");
                    continue;
                }
                for (int i = 0; i < value.size(); i++) {
                    element(field, value.get(i), here + "[" + i + "]", problems);
                }
            } else {
                element(field, value, here, problems);
            }
        }
        for (OneofDescriptor oneof : type.getOneofs()) {
            if (oneof.getFieldCount() == 1 && oneof.getField(0).toProto().getProto3Optional()) {
                continue;
            }
            boolean any = oneof.getFields().stream().anyMatch(f -> node.has(f.getJsonName()));
            if (!any) {
                problems.add(path + ": none of the " + oneof.getName() + " fields is set");
            }
        }
    }

    private static void element(FieldDescriptor field, JsonNode value, String path, List<String> problems) {
        switch (field.getJavaType()) {
            case MESSAGE -> {
                if (!OPAQUE.contains(field.getMessageType().getFullName())) {
                    walk(field.getMessageType(), value, path, problems);
                }
            }
            case ENUM -> {
                if (!value.isTextual()) {
                    problems.add(path + ": an enum must be written as its name, a string");
                }
            }
            default -> {
                // scalars are checked by the protobuf parser
            }
        }
    }

    private static boolean isRequired(FieldDescriptor field) {
        return field.getOptions().getExtension(FieldBehaviorProto.fieldBehavior).contains(FieldBehavior.REQUIRED);
    }

    /** The definition's own text, for tests that need to read it. */
    public static String definitionText() {
        try (InputStream in = OfficialDefinition.class.getResourceAsStream("/a2a.proto")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
