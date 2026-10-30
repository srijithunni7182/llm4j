package io.github.llm4j.eval.dataset;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A rubric as a list whose entries are either a line of text or {@code {text, dimension}}. Both give
 * the same list of texts; the dimensions, where written, are kept beside them.
 */
final class RubricDeserializer extends JsonDeserializer<List<String>> {

    @Override
    public List<String> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        List<String> texts = new ArrayList<>();
        List<String> dimensions = new ArrayList<>();
        JsonToken t = p.currentToken();
        if (t == JsonToken.VALUE_STRING) {
            texts.add(p.getValueAsString());
            dimensions.add(null);
        } else if (t == JsonToken.START_ARRAY) {
            while (p.nextToken() != JsonToken.END_ARRAY) {
                if (p.currentToken() == JsonToken.VALUE_STRING) {
                    texts.add(p.getValueAsString());
                    dimensions.add(null);
                } else if (p.currentToken() == JsonToken.START_OBJECT) {
                    JsonNode line = ctxt.readTree(p);
                    var names = line.fieldNames();
                    while (names.hasNext()) {
                        String name = names.next();
                        if (!name.equals("text") && !name.equals("dimension"))
                            throw JsonMappingException.from(
                                    p,
                                    "a rubric line takes text and dimension; "
                                            + name
                                            + " is not one of them");
                    }
                    String text = line.path("text").asText("");
                    if (text.isBlank())
                        throw JsonMappingException.from(
                                p, "a rubric line written as an object needs its text");
                    String dim = line.hasNonNull("dimension") ? line.get("dimension").asText() : null;
                    texts.add(text);
                    dimensions.add(dim == null || dim.isBlank() ? null : dim.strip());
                } else {
                    return (List<String>) ctxt.handleUnexpectedToken(List.class, p);
                }
            }
        } else {
            return (List<String>) ctxt.handleUnexpectedToken(List.class, p);
        }
        return DimensionedLines.of(texts, dimensions);
    }
}
