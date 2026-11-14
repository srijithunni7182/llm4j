package io.github.llm4j.tools.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One operation of an OpenAPI description: what a tool of this kind calls. Everything here came
 * from the specification, which is untrusted input: names are checked before they become a tool's
 * arguments, and text is shortened before it reaches a model.
 *
 * @param id the operation's name ({@code operationId}, or one derived from the method and path)
 * @param method the HTTP method, upper case
 * @param path the path template, such as {@code /pet/{petId}}
 * @param summary the specification's one-line description, or empty
 * @param parameters the parameters the caller supplies, in the order the specification lists them
 * @param body the request body, or null when the operation takes none
 */
public record ApiOperation(
        String id, String method, String path, String summary, List<Parameter> parameters, Body body) {

    /** Methods that only look something up. */
    static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /**
     * A parameter the caller supplies.
     *
     * @param name the name in the specification, as the server knows it
     * @param argument the name the tool's argument goes by (a name a provider accepts)
     * @param location {@code path}, {@code query} or {@code header}
     * @param required whether the call fails without it
     * @param schema the parameter's schema, with references resolved
     */
    public record Parameter(
            String name, String argument, String location, boolean required, JsonNode schema) {}

    /**
     * The request body.
     *
     * @param required whether the call fails without it
     * @param contentType {@code application/json} or {@code application/x-www-form-urlencoded}
     * @param schema the body's schema, with references resolved
     */
    public record Body(boolean required, String contentType, JsonNode schema) {}

    public ApiOperation {
        method = method.toUpperCase(Locale.ROOT);
        parameters = List.copyOf(parameters);
    }

    /** Whether a call only looks something up (GET, HEAD or OPTIONS). */
    public boolean isRead() {
        return READ_METHODS.contains(method);
    }
}
