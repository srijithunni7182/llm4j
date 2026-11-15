package io.github.llm4j.tools.openapi;

import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.tools.GenericTool;
import io.github.llm4j.tools.Limits;
import io.github.llm4j.tools.NetOptions;
import io.github.llm4j.tools.Redactor;
import io.github.llm4j.tools.ToolRefusal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every operation of a description as one tool, the way the {@code openapi} tool always worked:
 * the model gives {@code endpoint}, {@code method}, {@code parameters} and {@code body}. Kept so
 * scripts written before phase 36 keep working, on the shared HTTP layer; it exposes the whole
 * API, which {@code weave audit} reports (LA34), and goes away at the next major version.
 */
final class LegacyTool extends GenericTool {

    private static final int LISTED_OPERATIONS = 100;
    private static final int SUMMARY_CHARS = 120;
    private static final int DESCRIPTION_CHARS = 200;

    private final ApiDescription description;
    private final ApiClient client;
    private final NetOptions net;
    private final Map<String, TaskEffect> declared;

    LegacyTool(
            String name,
            ApiDescription description,
            ApiClient client,
            NetOptions net,
            Map<String, TaskEffect> declared,
            Redactor redactor,
            EffectContext context) {
        super(name, "openapi", describe(description), redactor, context);
        this.description = description;
        this.client = client;
        this.net = net;
        this.declared = Map.copyOf(declared);
    }

    /**
     * What the model is told, in the words the older tool used so a script's prompt still fits. The
     * specification's own text is untrusted, so each piece of it is cut to a short single line.
     */
    private static String describe(ApiDescription d) {
        StringBuilder out =
                new StringBuilder(
                        "Calls the "
                                + (d.title().isBlank() ? "HTTP" : Limits.excerpt(d.title(), SUMMARY_CHARS))
                                + " API");
        if (!d.description().isBlank())
            out.append(" (").append(Limits.excerpt(d.description(), DESCRIPTION_CHARS)).append(")");
        out.append(
                ". Arguments: {\"endpoint\": \"<path>\", \"method\": \"GET|POST|…\", "
                        + "\"parameters\": {<name>: <value>}, \"body\": {…}}. Endpoints:");
        int shown = 0;
        for (ApiOperation op : d.operations()) {
            if (shown++ == LISTED_OPERATIONS) {
                out.append("\n… and ").append(d.operations().size() - LISTED_OPERATIONS).append(" more");
                break;
            }
            out.append("\n- ").append(op.method()).append(' ').append(op.path());
            if (!op.summary().isBlank()) out.append(": ").append(Limits.excerpt(op.summary(), SUMMARY_CHARS));
            if (!op.parameters().isEmpty()) {
                out.append(" [params: ");
                out.append(
                        String.join(
                                ", ",
                                op.parameters().stream()
                                        .map(
                                                p ->
                                                        p.name()
                                                                + " ("
                                                                + p.location()
                                                                + (p.schema().path("type").asText("").isEmpty()
                                                                        ? ""
                                                                        : ", " + p.schema().path("type").asText())
                                                                + ")")
                                        .toList()));
                out.append("]");
            }
        }
        return out.toString();
    }

    private ApiOperation find(Map<String, Object> args) {
        Object endpoint = args.get("endpoint");
        Object method = args.get("method");
        if (!(endpoint instanceof String) || !(method instanceof String))
            throw new ToolRefusal("'endpoint' and 'method' arguments must be strings");
        return description.operations().stream()
                .filter(o -> o.path().equals(endpoint) && o.method().equalsIgnoreCase((String) method))
                .findFirst()
                .orElseThrow(
                        () ->
                                new ToolRefusal(
                                        "Endpoint '" + method + " " + endpoint + "' not found in API specification"));
    }

    /**
     * What the developer declared for the operation, else for the tool as a whole ({@code *}), else
     * what its HTTP method says.
     */
    private boolean changes(ApiOperation op) {
        TaskEffect effect = declared.getOrDefault(op.id(), declared.get("*"));
        return effect != null ? effect == TaskEffect.CHANGES : !op.isRead();
    }

    @Override
    public boolean isEffect(Map<String, Object> args) {
        try {
            return changes(find(args));
        } catch (ToolRefusal unknown) {
            return true; // a call that finds nothing is journaled as the cautious kind
        }
    }

    @Override
    public EffectPolicy policy() {
        return net.policy(0);
    }

    @Override
    public String target(Map<String, Object> args) {
        Object method = args.get("method");
        Object endpoint = args.get("endpoint");
        return String.valueOf(method).toUpperCase(Locale.ROOT) + " " + client.host() + endpoint;
    }

    @Override
    protected String run(Map<String, Object> args, String idempotencyKey) {
        ApiOperation op = find(args);
        // the older tool sent whatever body the model gave to a POST, PUT or PATCH, whether or not
        // the description declared one
        if (op.body() == null && args.get("body") != null && !op.isRead() && !op.method().equals("DELETE"))
            op =
                    new ApiOperation(
                            op.id(),
                            op.method(),
                            op.path(),
                            op.summary(),
                            op.parameters(),
                            new ApiOperation.Body(false, "application/json", null));
        Object given = args.get("parameters");
        Map<?, ?> parameters = given instanceof Map<?, ?> m ? m : Map.of();
        Map<String, Object> typed = new LinkedHashMap<>();
        for (ApiOperation.Parameter p : op.parameters()) {
            Object value = parameters.get(p.name());
            if (value != null) typed.put(p.argument(), value);
        }
        if (args.get("body") != null) typed.put("body", args.get("body"));
        ArgumentCheck.check(op, typed);
        return client.call(op, typed, idempotencyKey);
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "endpoint", Map.of("type", "string"),
                                "method", Map.of("type", "string"),
                                "parameters", Map.of("type", "object"),
                                "body", Map.of("type", "object")),
                "required", List.of("endpoint", "method"));
    }
}
