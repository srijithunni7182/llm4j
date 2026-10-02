package io.github.llm4j.tools;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.EffectPolicy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

/** Sends one request to a base URL, for an agent that supplies the path, method, query and body. */
final class HttpTool extends GenericTool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KNOWN_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    /** A declaration, parsed and checked. */
    record Config(HttpUrl baseUrl, Set<String> methods, RequestPath.Patterns paths, List<String> pathPatterns, String authHeader,
                  String authQuery, String authValue, Map<String, String> headers, long maxBytes, NetOptions net) {

        static Config parse(Options o) {
            HttpUrl base = HttpUrl.parse(o.require("base_url"));
            if (base == null) throw new OptionException("base_url: is not a valid URL");
            if (base.query() != null || base.fragment() != null) throw new OptionException("base_url: must not contain a query or fragment");
            NetOptions net = NetOptions.parse(o, 2, true);
            String problem = new NetPolicy(net.rules(), NetPolicy.Resolver.SYSTEM, base.host()).checkUrl(base);
            if (problem != null) throw new OptionException("base_url: " + problem);

            List<String> methodList = o.list("methods").stream().map(m -> m.toUpperCase(Locale.ROOT)).toList();
            for (String m : methodList) {
                if (!KNOWN_METHODS.contains(m)) throw new OptionException("methods: " + m + " is not one of GET, POST, PUT, PATCH, DELETE");
            }
            Set<String> methods = methodList.isEmpty() ? Set.of("GET") : Set.copyOf(methodList);

            List<String> patterns = o.list("allow_paths");
            RequestPath.Patterns paths = new RequestPath.Patterns(patterns);

            boolean header = o.has("auth_header");
            boolean query = o.has("auth_query");
            if (header && query) throw new OptionException("use auth_header or auth_query, not both");
            if ((header || query) != o.has("auth_value")) throw new OptionException("auth_value goes with exactly one of auth_header or auth_query");
            return new Config(base, methods, paths, patterns, o.get("auth_header"), o.get("auth_query"), o.get("auth_value"),
                    o.headers(), o.size("max_bytes", 64 * 1024, 4L * 1024 * 1024), net);
        }
    }

    private final Config config;
    private final HttpSupport http;

    HttpTool(String name, Config config, HttpSupport http, Redactor redactor, EffectContext context) {
        super(name, "http", description(config), redactor, context);
        this.config = config;
        this.http = http;
    }

    private static String description(Config c) {
        String base = c.baseUrl().scheme() + "://" + c.baseUrl().host() + c.baseUrl().encodedPath();
        return "Calls an HTTP API at " + base + ". Arguments: path (required, starts with /, relative to that address"
                + (c.pathPatterns().isEmpty() ? "" : "; allowed: " + String.join(", ", c.pathPatterns()))
                + "), method (" + String.join(" or ", c.methods().stream().sorted().toList()) + "; default GET), "
                + "query (an object of strings), body (a string, or an object sent as JSON; not for GET). "
                + "Returns the status line, content type and body. Only text responses are returned.";
    }

    // ── Effect hooks ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean isEffect(Map<String, Object> args) {
        return !"GET".equals(methodOf(args));
    }

    @Override
    public EffectPolicy policy() {
        return config.net().policy(0);
    }

    @Override
    public String target(Map<String, Object> args) {
        Object p = args.get("path");
        return methodOf(args) + " " + config.baseUrl().host() + (p == null ? "" : Limits.excerpt(String.valueOf(p).split("[?#]")[0], 160));
    }

    private static String methodOf(Map<String, Object> args) {
        Object m = args.get("method");
        return m == null || String.valueOf(m).isBlank() ? "GET" : String.valueOf(m).trim().toUpperCase(Locale.ROOT);
    }

    // ── The call ─────────────────────────────────────────────────────────────────────────────

    @Override
    protected String run(Map<String, Object> args, String idempotencyKey) {
        String method = methodOf(args);
        if (!config.methods().contains(method)) {
            throw new ToolRefusal("method " + method + " is not allowed (this tool allows " + String.join(", ", config.methods().stream().sorted().toList()) + ")");
        }
        String path = RequestPath.validate(optionalText(args, "path"));
        if (!config.paths().matches(path)) throw new ToolRefusal("path " + path + " is not one this tool may call");

        HttpUrl url = buildUrl(path, args.get("query"));
        Request.Builder request = new Request.Builder().url(url);
        config.headers().forEach(request::header);
        if (config.authHeader() != null) request.header(config.authHeader(), config.authValue());
        boolean effect = !method.equals("GET");
        if (effect && config.net().idempotency() && idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        request.method(method, bodyFor(method, args.get("body")));

        HttpSupport.Retry retry = !effect || config.net().idempotency() ? HttpSupport.Retry.ALL : HttpSupport.Retry.NONE;
        HttpSupport.Reply reply = http.send(request.build(), retry);
        String result = render(reply);
        if (effect && !reply.success()) throw new ToolRefusal(result);
        return result;
    }

    private HttpUrl buildUrl(String path, Object query) {
        String basePath = config.baseUrl().encodedPath();
        if (basePath.endsWith("/")) basePath = basePath.substring(0, basePath.length() - 1);
        HttpUrl.Builder b = config.baseUrl().newBuilder().encodedPath(basePath + path);
        if (query instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (v instanceof Iterable<?> many) many.forEach(item -> b.addQueryParameter(String.valueOf(k), String.valueOf(item)));
                else b.addQueryParameter(String.valueOf(k), String.valueOf(v));
            });
        } else if (query != null) {
            throw new ToolRefusal("query must be an object of strings");
        }
        if (config.authQuery() != null) b.addQueryParameter(config.authQuery(), config.authValue());
        return b.build();
    }

    private static RequestBody bodyFor(String method, Object body) {
        boolean takesBody = method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
        if (body == null || (body instanceof String s && s.isEmpty())) {
            return takesBody ? RequestBody.create(new byte[0], null) : null;
        }
        if (!takesBody) throw new ToolRefusal(method + " requests don't take a body");
        if (body instanceof String s) return RequestBody.create(s, MediaType.get("text/plain; charset=utf-8"));
        try {
            return RequestBody.create(JSON.writeValueAsString(body), MediaType.get("application/json; charset=utf-8"));
        } catch (JsonProcessingException e) {
            throw new ToolRefusal("body can't be written as JSON");
        }
    }

    private String render(HttpSupport.Reply reply) {
        String head = "HTTP " + reply.status() + (reply.reason() == null || reply.reason().isEmpty() ? "" : " " + reply.reason());
        if (reply.body().length == 0) return head;
        String type = reply.contentType();
        if (!isTextual(type)) throw new ToolRefusal(head + ": the response is " + (type == null ? "of unknown type" : type) + ", not text, so it isn't returned");
        String text = reply.bodyText();
        if (reply.truncated()) text += Limits.markerUnknownTotal(reply.body().length);
        return head + "\nContent-Type: " + type + "\n\n" + text;
    }

    static boolean isTextual(String contentType) {
        if (contentType == null) return false;
        String t = contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        return t.startsWith("text/") || t.equals("application/json") || t.equals("application/xml")
                || t.equals("application/x-www-form-urlencoded") || t.endsWith("+json") || t.endsWith("+xml");
    }
}
