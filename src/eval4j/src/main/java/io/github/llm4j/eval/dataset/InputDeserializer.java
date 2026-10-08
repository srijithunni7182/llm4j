package io.github.llm4j.eval.dataset;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;

/** {@code input:} is text. A mapping there is a mistake people make for a workflow with several parameters, so the message says what to write instead. */
final class InputDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken t = p.currentToken();
        if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY) {
            throw JsonMappingException.from(p, "input: must be text (the agent's task, or a workflow's first parameter); "
                    + "for a workflow with several parameters write inputs: { name: value, ... } instead");
        }
        return p.getValueAsString();
    }
}
