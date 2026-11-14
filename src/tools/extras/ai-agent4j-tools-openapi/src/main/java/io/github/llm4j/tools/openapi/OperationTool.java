package io.github.llm4j.tools.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.tools.GenericTool;
import io.github.llm4j.tools.Limits;
import io.github.llm4j.tools.NetOptions;
import io.github.llm4j.tools.Redactor;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One operation of an OpenAPI description as one tool (phase 36a, D3): its arguments are the
 * operation's own parameters and body, typed from the description, so the model never writes a
 * path or a method and approval, effect and audit are per operation.
 */
final class OperationTool extends GenericTool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int SUMMARY_CHARS = 200;

    private final ApiOperation operation;
    private final ApiClient client;
    private final NetOptions net;

    OperationTool(
            String name,
            ApiOperation operation,
            ApiClient client,
            NetOptions net,
            Redactor redactor,
            EffectContext context) {
        super(name, "openapi", description(operation), redactor, context);
        this.operation = operation;
        this.client = client;
        this.net = net;
    }

    /** The specification's text is untrusted, so only a short single line of it is shown. */
    private static String description(ApiOperation op) {
        String summary = op.summary().isBlank() ? "" : " " + Limits.excerpt(op.summary(), SUMMARY_CHARS);
        return "Calls " + op.method() + " " + op.path() + "." + summary;
    }

    @Override
    public boolean isEffect(Map<String, Object> args) {
        return !operation.isRead();
    }

    @Override
    public EffectPolicy policy() {
        return net.policy(0);
    }

    @Override
    public String target(Map<String, Object> args) {
        return operation.method() + " " + client.host() + operation.path();
    }

    @Override
    protected String run(Map<String, Object> args, String idempotencyKey) {
        ArgumentCheck.check(operation, args);
        return client.call(operation, args, idempotencyKey);
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        ObjectNode schema = JSON.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        var required = schema.putArray("required");
        for (ApiOperation.Parameter p : operation.parameters()) {
            properties.set(p.argument(), p.schema());
            if (p.required()) required.add(p.argument());
        }
        if (operation.body() != null) {
            properties.set("body", operation.body().schema());
            if (operation.body().required()) required.add("body");
        }
        if (required.isEmpty()) schema.remove("required");
        schema.put("additionalProperties", false);
        return JSON.convertValue(schema, LinkedHashMap.class);
    }
}
