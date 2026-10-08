package io.github.llm4j.eval.dataset;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@code inputs:} is a mapping of a workflow's parameter names to text; numbers and booleans are read as the text they were written as. */
final class InputsDeserializer extends JsonDeserializer<Map<String, String>> {

    @Override
    public Map<String, String> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() != JsonToken.START_OBJECT) {
            throw JsonMappingException.from(p, "inputs: must be a mapping of a workflow's parameter names to values, for example inputs: { topic: \"pricing\", platform: linkedin }");
        }
        Map<String, String> out = new LinkedHashMap<>();
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String key = p.currentName();
            JsonToken v = p.nextToken();
            if (v == JsonToken.START_OBJECT || v == JsonToken.START_ARRAY) {
                throw JsonMappingException.from(p, "inputs." + key + ": must be text, a number or true/false");
            }
            out.put(key, v == JsonToken.VALUE_NULL ? "" : p.getValueAsString());
        }
        return out;
    }
}
