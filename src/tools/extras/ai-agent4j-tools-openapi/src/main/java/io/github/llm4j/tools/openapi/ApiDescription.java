package io.github.llm4j.tools.openapi;

import java.util.List;
import java.util.Optional;

/**
 * An OpenAPI description, reduced to what a tool needs.
 *
 * @param title the description's title
 * @param description the description's own text about the API, or empty
 * @param version the API's version as the description states it
 * @param servers the {@code servers} entries, with variables filled by their defaults
 * @param operations every operation this kind can call
 */
public record ApiDescription(
        String title,
        String description,
        String version,
        List<String> servers,
        List<ApiOperation> operations) {

    public ApiDescription {
        servers = List.copyOf(servers);
        operations = List.copyOf(operations);
    }

    /** The operation with this id, if there is one. */
    public Optional<ApiOperation> operation(String id) {
        return operations.stream().filter(o -> o.id().equals(id)).findFirst();
    }
}
