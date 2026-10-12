package io.github.llm4j.loom.inspect.wire;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The few Loom-specific messages that have no OpenTelemetry equivalent: JSON, versioned, tolerant of fields it does not know. */
public final class Wire {

    /** The version of these messages. A newer process talking to an older server degrades: unknown fields are ignored. */
    public static final int VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Wire() { }

    public static String write(Object message) {
        try {
            return MAPPER.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not a valid " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }
}
