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

    private static final int LISTED_OPERATIONS = 60;
    private static final int SUMMARY_CHARS = 80;

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

    private static String describe(ApiDescription d) {
        StringBuilder out =
                new StringBuilder("Calls the " + Limits.excerpt(d.title(), SUMMARY_CHARS) + " API. Arguments: ");
        out.append("endpoint (a path from the list), method, parameters (an object of the path, query and ")
                .append("header values), body (an object, for POST, PUT and PATCH). Operations:");
        int shown = 0;
        for (ApiOperation op : d.operations()) {
            if (shown++ == LISTED_OPERATIONS) {
                out.append("\n… and ").append(d.operations().size() - LISTED_OPERATIONS).append(" more");
                break;
            }
            out.append("\n- ").append(op.method()).append(' ').append(op.path());
            if (!op.summary().isBlank()) out.append(": ").append(Limits.excerpt(op.summary(), SUMMARY_CHARS));
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

    /** What the developer declared for the operation, else what its method says. */
    private boolean changes(ApiOperation op) {
        TaskEffect effect = declared.get(op.id());
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
