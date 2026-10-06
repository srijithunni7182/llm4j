package io.github.llm4j.eval.dataset;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tags as a list ({@code [kind:refund, risky]}) or as a mapping ({@code {kind: refund}}), which reads as the tag {@code kind:refund}.
 * Both give the same list, so a dataset can be written whichever way reads best.
 */
final class TagsDeserializer extends JsonDeserializer<List<String>> {

    @Override
    public List<String> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        List<String> out = new ArrayList<>();
        JsonToken t = p.currentToken();
        if (t == JsonToken.START_OBJECT) {
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String key = p.currentName();
                p.nextToken();
                out.add(key + ":" + p.getValueAsString());
            }
        } else if (t == JsonToken.START_ARRAY) {
            while (p.nextToken() != JsonToken.END_ARRAY) {
                out.add(p.getValueAsString());
            }
        } else if (t == JsonToken.VALUE_STRING) {
            out.add(p.getValueAsString());
        } else {
            return (List<String>) ctxt.handleUnexpectedToken(List.class, p);
        }
        return out;
    }
}
