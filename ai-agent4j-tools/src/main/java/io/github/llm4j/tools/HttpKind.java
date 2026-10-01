package io.github.llm4j.tools;

import io.github.llm4j.agent.Tool;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import okhttp3.HttpUrl;

/**
 * {@code tool Github { use: http  base_url: "https://api.github.com"  auth_header: "Authorization"
 * auth_value: env.TOKEN }}: lets an agent call a REST API that has no OpenAPI spec. The agent chooses a
 * path below the base URL (and a method, query and body); never the host or the headers.
 */
public final class HttpKind extends GenericKind {

    @Override
    public String name() {
        return "http";
    }

    @Override
    public Set<String> required() {
        return Set.of("base_url");
    }

    @Override
    public Set<String> optional() {
        Set<String> s = new HashSet<>(NetOptions.NAMES);
        s.addAll(Set.of("methods", "allow_paths", "auth_header", "auth_query", "auth_value", "max_bytes", "follow_redirects"));
        return s;
    }

    @Override
    public Set<String> secrets() {
        return Set.of("auth_value");
    }

    @Override
    public Set<String> prefixes() {
        return Set.of(Options.HEADER_PREFIX);
    }

    @Override
    protected void validate(Options o, Path baseDir) {
        HttpTool.Config.parse(o);
    }

    @Override
    protected Tool build(String name, Options o, Path baseDir, EffectContext context) {
        HttpTool.Config c = HttpTool.Config.parse(o);
        HttpUrl base = c.baseUrl();
        NetPolicy policy = new NetPolicy(c.net().rules(), NetPolicy.Resolver.SYSTEM, base.host());
        HttpSupport http = new HttpSupport(policy, context, c.net().timeout(), c.maxBytes(), c.net().retries());
        return new HttpTool(name, c, http, redactor(o), context);
    }
}
