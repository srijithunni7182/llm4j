package io.github.llm4j.model;

import java.util.Map;
import java.util.Objects;

/**
 * A request for a reply that is JSON following a schema: a name, a JSON Schema whose root is an
 * object, and whether the provider must enforce it strictly. It is a field of the request, not a
 * vendor parameter, so a wrapper client passes it on and a provider that cannot honour it can tell
 * and leave it out. Whether the reply really matches is still the caller's to check: a refusal or a
 * reply cut at the token limit does not.
 *
 * @param name a short label for the schema (providers require one)
 * @param schema the JSON Schema, an object schema; copied, and unmodifiable
 * @param strict whether the provider must enforce the schema exactly
 */
public record ResponseFormat(String name, Map<String, Object> schema, boolean strict) {

    /** Checks the parts and copies the schema. */
    public ResponseFormat {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("a response format needs a name");
        Objects.requireNonNull(schema, "schema");
        if (!"object".equals(schema.get("type")))
            throw new IllegalArgumentException(
                    "the schema of a response format must be an object schema (type: object), not "
                            + schema.get("type"));
        schema = Map.copyOf(schema);
    }

    /** A strict JSON Schema format. */
    public static ResponseFormat jsonSchema(String name, Map<String, Object> schema) {
        return new ResponseFormat(name, schema, true);
    }
}
