package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The matcher {@code contains: text}, built in code. */
final class YAMLContains {

    private YAMLContains() {}

    static JsonNode of(String text) {
        return new ObjectMapper().createObjectNode().put("contains", text);
    }
}
