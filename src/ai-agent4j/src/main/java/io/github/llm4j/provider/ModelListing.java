package io.github.llm4j.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.exception.InvalidRequestException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the model list that OpenAI-style and Anthropic APIs return: {@code {"data":[{"id":"…"}]}}.
 * Shared by the providers that implement {@link DescribableProvider} so each only names its path.
 */
public final class ModelListing {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ModelListing() {}

    /** The ids in {@code body}'s {@code data} array, in the order the vendor sent them. */
    public static String[] ids(String provider, String body) {
        try {
            JsonNode data = JSON.readTree(body).path("data");
            if (!data.isArray()) {
                throw new InvalidRequestException(
                        provider + " returned a model list without a data array");
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode model : data) {
                String id = model.path("id").asText("");
                if (!id.isEmpty()) ids.add(id);
            }
            return ids.toArray(new String[0]);
        } catch (JsonProcessingException e) {
            throw Providers.typed(provider, "Failed to read the model list", e);
        }
    }

    /** The first id of {@code ids}, or null when the list is empty. */
    public static String first(String[] ids) {
        return ids.length == 0 ? null : ids[0];
    }
}
