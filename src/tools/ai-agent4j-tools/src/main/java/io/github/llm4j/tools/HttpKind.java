package io.github.llm4j.tools;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.ToolProfile;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import okhttp3.HttpUrl;

/**
 * {@code tool Github { use: http base_url: "https://api.github.com" auth_header: "Authorization"
 * auth_value: env.TOKEN }}: lets an agent call a REST API that has no OpenAPI spec. The agent
 * chooses a path below the base URL (and a method, query and body); never the host or the headers.
 */
public final class HttpKind extends GenericKind {

    /** The option that names a sandbox whose served program the tool calls instead of a base URL. */
    static final String REACHES = "reaches";

    @Override
    public String name() {
        return "http";
    }

    @Override
    public Set<String> required() {
        return Set.of();
    }

    @Override
    public Set<String> optional() {
        Set<String> s = new HashSet<>(NetOptions.NAMES);
        s.addAll(
                Set.of(
                        "methods",
                        "allow_paths",
                        "auth_header",
                        "auth_query",
                        "auth_value",
                        "max_bytes",
                        "follow_redirects",
                        "base_url",
                        REACHES));
        return s;
    }

    @Override
    public Set<String> secrets() {
        return Set.of("auth_value");
    }

    @Override
    public Set<String> prefixes() {
        return Set.of(Options.HEADER_PREFIX, REACHES + ".");
    }

    /** {@code reaches: Py}: the sandbox whose served program this tool calls (D7). */
    @Override
    public Map<String, String> references() {
        return Map.of(REACHES, "sandbox");
    }

    @Override
    protected void validate(Options o, Path baseDir) {
        HttpTool.Config.parse(o);
    }

    @Override
    protected Tool build(String name, Options o, Path baseDir, EffectContext context) {
        HttpTool.Config c = HttpTool.Config.parse(o);
        HttpUrl base = c.baseUrl();
        if (c.reaches() == null) {
            NetPolicy policy =
                    new NetPolicy(c.net().rules(), NetPolicy.Resolver.SYSTEM, base.host());
            HttpSupport http =
                    new HttpSupport(
                            policy, context, c.net().timeout(), c.maxBytes(), c.net().retries());
            return new HttpTool(name, c, http, null, redactor(o), context);
        }
        Leases leases = new Leases(SandboxKind.store(o, REACHES + ".", baseDir), context.clock());
        NetPolicy policy =
                NetPolicy.servedPort(
                        c.net().rules(),
                        NetPolicy.Resolver.SYSTEM,
                        SandboxKind.servedPorts(o.values(), baseDir, context.clock()));
        HttpSupport http =
                new HttpSupport(
                        policy, context, c.net().timeout(), c.maxBytes(), c.net().retries());
        return new HttpTool(name, c, http, leases, redactor(o), context);
    }

    /** What a tool of this kind reaches, for an audit; its calls go through the effect journal. */
    /**
     * With {@code reaches:}, the answer comes from a program the sandbox serves, which a model may
     * have written: the whole result is unchecked model text, as a sandbox's own output is. A base
     * URL's answer stays the tool's data.
     */
    @Override
    public Set<String> uncheckedResult(Map<String, String> options) {
        return options.containsKey(REACHES) ? Set.of(WHOLE_RESULT) : Set.of();
    }

    @Override
    public ToolProfile profile(Map<String, String> options) {
        String reaches = options.get(REACHES);
        String baseUrl =
                reaches != null
                        ? "the program " + reaches + " serves on this machine (one port, while it runs)"
                        : options.get("base_url");
        String methods = options.get("methods");
        boolean writes = writes(methods);
        return ToolProfile.of(
                        true,
                        options.get("auth_value") != null || options.get("auth_header") != null,
                        writes,
                        writes,
                        writes ? "calls " + baseUrl + " with " + methods : "reads from " + baseUrl)
                .journaled(true);
    }

    /** Whether {@code methods} (a comma-separated list) names any method other than GET. */
    private static boolean writes(String methods) {
        if (methods == null) return false;
        for (String m : methods.split(","))
            if (!m.strip().equalsIgnoreCase("GET") && !m.isBlank()) return true;
        return false;
    }
}
