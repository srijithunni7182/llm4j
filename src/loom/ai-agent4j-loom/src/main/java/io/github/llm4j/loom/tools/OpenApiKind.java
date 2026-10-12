package io.github.llm4j.loom.tools;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tools.openapi.OpenAPIParser;
import io.github.llm4j.agent.tools.openapi.OpenAPITool;
import io.github.llm4j.secret.CredentialSource;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * {@code tool Refunds { use: openapi  spec: "refunds.json"  auth_header: "Authorization"  auth_value: env.TOKEN }}: every operation of an OpenAPI
 * description as one tool. {@code auth_value} may also be {@code invoker.NAME}, a credential of whoever runs the workflow, asked for on every request.
 */
final class OpenApiKind implements ToolKind {

    @Override
    public String name() {
        return "openapi";
    }

    @Override
    public Set<String> required() {
        return Set.of("spec");
    }

    @Override
    public Set<String> optional() {
        return Set.of("auth_header", "auth_query", "auth_value");
    }

    @Override
    public Set<String> secrets() {
        return Set.of("auth_value");
    }

    @Override
    public Set<String> lateBound() {
        return Set.of("auth_value");
    }

    @Override
    public String check(Map<String, String> o) {
        boolean header = o.containsKey("auth_header");
        boolean query = o.containsKey("auth_query");
        if (header && query) return "use auth_header or auth_query, not both";
        if ((header || query) != o.containsKey("auth_value")) {
            return "auth_value goes with exactly one of auth_header or auth_query";
        }
        return null;
    }

    @Override
    public Tool create(String name, Map<String, String> o, Path dir) {
        return create(name, o, Map.of(), dir, EffectContext.noop());
    }

    @Override
    public Tool create(String name, Map<String, String> o, Map<String, CredentialSource> lateBound, Path dir, EffectContext context) {
        String spec = o.get("spec");
        String location = spec.matches("(?i)https?://.*") || Path.of(spec).isAbsolute() ? spec : dir.resolve(spec).toString();
        OpenAPITool.Builder tool = OpenAPITool.builder().name(name).spec(OpenAPIParser.parse(location));
        // a value written invoker.NAME is asked for on every request; any other is the value itself
        CredentialSource asked = lateBound.get("auth_value");
        if (o.containsKey("auth_header")) {
            if (asked != null) tool.headerAuth(o.get("auth_header"), asked);
            else tool.headerAuth(o.get("auth_header"), o.get("auth_value"));
        }
        if (o.containsKey("auth_query")) {
            if (asked != null) tool.apiKeyAuth(o.get("auth_query"), asked);
            else tool.apiKeyAuth(o.get("auth_query"), o.get("auth_value"));
        }
        return tool.build();
    }
}
