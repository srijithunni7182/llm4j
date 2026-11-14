package io.github.llm4j.tools.openapi;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.ToolProfile;
import io.github.llm4j.secret.CredentialSource;
import io.github.llm4j.tools.GenericKind;
import io.github.llm4j.tools.HttpSupport;
import io.github.llm4j.tools.NetOptions;
import io.github.llm4j.tools.NetPolicy;
import io.github.llm4j.tools.OptionException;
import io.github.llm4j.tools.Options;
import io.github.llm4j.tools.SafePaths;
import io.github.llm4j.tools.ToolRefusal;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.HttpUrl;
import okhttp3.Request;

/**
 * {@code tool Pet_get { use: openapi spec: "petstore.json" operation: "getPetById" effect: reads
 * }}: one operation of an OpenAPI description as one tool (phase 36a, D2 and D3). Without {@code
 * operation:} the tool exposes every operation, as the older tool did, and the audit says so.
 *
 * <p>Every request goes through the shared HTTP layer, so the address rules, timeouts, size caps and
 * retries are the ones {@code http} has. The description and each file or address it points at are
 * read by this module, never by the parser. Its {@code servers} entry is untrusted: unless the
 * declaration names {@code base_url}, a loopback, private or metadata address is refused.
 */
public final class OpenApiKind extends GenericKind {

    private static final Pattern OPERATION = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final int LISTED_ON_MISS = 20;

    private final SSLSocketFactory sslSocketFactory;
    private final X509TrustManager trust;

    /** The kind as {@code ServiceLoader} makes it: the JDK's certificate authorities. */
    public OpenApiKind() {
        this(null, null);
    }

    /** A kind that trusts {@code trust} instead, for a private authority or a test server. */
    OpenApiKind(SSLSocketFactory sslSocketFactory, X509TrustManager trust) {
        this.sslSocketFactory = sslSocketFactory;
        this.trust = trust;
    }

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
        Set<String> s = new HashSet<>(NetOptions.NAMES);
        s.addAll(
                Set.of(
                        "operation",
                        "base_url",
                        "server",
                        "max_bytes",
                        "save_dir",
                        "save_max_bytes",
                        "auth_header",
                        "auth_query",
                        "auth_value",
                        "pin",
                        "follow_redirects",
                        "effects"));
        return s;
    }

    @Override
    public Set<String> secrets() {
        return Set.of("auth_value");
    }

    @Override
    public Set<String> lateBound() {
        return Set.of("auth_value");
    }

    /** The tool is built when the run starts, so a missing operation or spec is a load error. */
    @Override
    public boolean buildsAtRunStart() {
        return true;
    }

    // ── What the audit is told ───────────────────────────────────────────────────────────────

    /**
     * What a tool of this kind reaches. It reads untrusted content, reaches private data when it
     * carries a credential, and sends outward only if its effect is {@code changes}; the effect is
     * the declared one (passed as {@code effect}), else {@code changes} for a tool that names one
     * operation, because the audit cannot open the description to see its method.
     */
    @Override
    public ToolProfile profile(Map<String, String> options) {
        boolean auth =
                options.containsKey("auth_value")
                        || options.containsKey("auth_header")
                        || options.containsKey("auth_query");
        String declared = options.get("effect");
        boolean declaredHarmless = "reads".equals(declared) || "none".equals(declared);
        boolean changes = !declaredHarmless;
        boolean saves = options.containsKey("save_dir");
        String what =
                options.containsKey("operation")
                        ? "operation " + options.get("operation")
                        : "any operation";
        String note =
                what
                        + " of "
                        + options.get("spec")
                        + (declared == null
                                ? " (effect assumed changes: declare effect: reads, or run weave connect)"
                                : "")
                        + (saves ? "; saves long or non-text answers to " + options.get("save_dir") : "");
        return ToolProfile.of(true, auth, changes, changes || saves, note).journaled(true);
    }

    // ── Checking the declaration (no network, no files) ──────────────────────────────────────

    @Override
    protected void validate(Options o, Path baseDir) {
        String spec = o.require("spec");
        if (spec.isBlank()) throw new OptionException("spec: is empty");
        NetOptions net = NetOptions.parse(o, 2, true);
        String operation = o.get("operation");
        if (operation != null && !OPERATION.matcher(operation).matches())
            throw new OptionException(
                    "operation: is an operationId (letters, digits, _ . and -), got " + operation);
        if (o.has("pin") && operation == null)
            throw new OptionException("pin: goes with operation: (a pin covers one operation)");
        if (o.has("pin") && !o.get("pin").startsWith(SpecPin.PREFIX))
            throw new OptionException("pin: is written " + SpecPin.PREFIX + "<hex>, as weave connect writes it");
        boolean header = o.has("auth_header");
        boolean query = o.has("auth_query");
        if (header && query) throw new OptionException("use auth_header or auth_query, not both");
        if ((header || query) != o.has("auth_value"))
            throw new OptionException("auth_value goes with exactly one of auth_header or auth_query");
        o.size("max_bytes", Answers.DEFAULT_MAX_BYTES, Answers.CEILING_MAX_BYTES);
        long saveMax = o.size("save_max_bytes", Answers.DEFAULT_SAVE_BYTES, Answers.CEILING_SAVE_BYTES);
        if (o.has("save_max_bytes") && !o.has("save_dir"))
            throw new OptionException("save_max_bytes: goes with save_dir:");
        if (saveMax < o.size("max_bytes", Answers.DEFAULT_MAX_BYTES, Answers.CEILING_MAX_BYTES))
            throw new OptionException("save_max_bytes: must be at least max_bytes");
        if (o.has("base_url")) {
            HttpUrl base = HttpUrl.parse(o.get("base_url"));
            if (base == null) throw new OptionException("base_url: is not a valid URL");
            if (base.query() != null || base.fragment() != null)
                throw new OptionException("base_url: must not contain a query or fragment");
            String problem =
                    new NetPolicy(net.rules(), NetPolicy.Resolver.SYSTEM, base.host()).checkUrl(base);
            if (problem != null) throw new OptionException("base_url: " + problem);
        }
        if (o.has("server") && HttpUrl.parse(o.get("server")) == null)
            throw new OptionException("server: is not a valid URL");
        effects(o);
    }

    /** The legacy tool's per-operation effects, {@code id=reads,id2=changes}. */
    private static Map<String, TaskEffect> effects(Options o) {
        Map<String, TaskEffect> out = new LinkedHashMap<>();
        for (String entry : o.list("effects")) {
            String[] parts = entry.split("=", 2);
            TaskEffect effect =
                    parts.length == 2
                            ? switch (parts[1].strip()) {
                                case "none" -> TaskEffect.NONE;
                                case "reads" -> TaskEffect.READS;
                                case "changes" -> TaskEffect.CHANGES;
                                default -> null;
                            }
                            : null;
            if (effect == null)
                throw new OptionException("effects: entries are id=none|reads|changes, got " + entry);
            out.put(parts[0].strip(), effect);
        }
        return out;
    }

    // ── Building the tool ────────────────────────────────────────────────────────────────────

    @Override
    protected Tool build(String name, Options o, Path baseDir, EffectContext context) {
        return build(name, o, Map.of(), baseDir, context);
    }

    @Override
    protected Tool build(
            String name,
            Options o,
            Map<String, CredentialSource> lateBound,
            Path baseDir,
            EffectContext context) {
        NetOptions net = NetOptions.parse(o, 2, true);
        String spec = o.require("spec");
        boolean remote = spec.matches("(?i)https?://.*");
        HttpUrl specUrl = remote ? HttpUrl.parse(spec) : null;
        ApiDescription api =
                SpecLoader.load(spec, baseDir, fetcher(net, specUrl == null ? null : specUrl.host(), context));

        HttpUrl base = baseUrl(o, api, specUrl);
        Answers.Settings answers =
                new Answers.Settings(
                        o.size("max_bytes", Answers.DEFAULT_MAX_BYTES, Answers.CEILING_MAX_BYTES),
                        o.has("save_dir") ? safeDir(baseDir, o.get("save_dir")) : null,
                        o.size("save_max_bytes", Answers.DEFAULT_SAVE_BYTES, Answers.CEILING_SAVE_BYTES));
        // the host the declaration names is trusted (loopback allowed there); one the description names is not
        String trusted = o.has("base_url") ? base.host() : null;
        HttpSupport http =
                new HttpSupport(
                        new NetPolicy(net.rules(), NetPolicy.Resolver.SYSTEM, trusted),
                        context,
                        net.timeout(),
                        answers.readLimit(),
                        net.retries(),
                        sslSocketFactory,
                        trust);
        CredentialSource asked = lateBound.get("auth_value");
        Credential credential =
                o.has("auth_header") || lateBound.containsKey("auth_value") && !o.has("auth_query")
                        ? new Credential(o.get("auth_header"), null, o.get("auth_value"), asked)
                        : o.has("auth_query")
                                ? new Credential(null, o.get("auth_query"), o.get("auth_value"), asked)
                                : Credential.NONE;
        List<String> fixed = o.has("auth_value") ? List.of(o.get("auth_value")) : List.of();
        ApiClient client = new ApiClient(name, base, net, credential, answers, http, fixed);

        if (o.has("operation")) {
            ApiOperation op =
                    api.operation(o.get("operation"))
                            .orElseThrow(() -> new IllegalArgumentException(missing(o.get("operation"), api, spec)));
            return new OperationTool(name, op, client, net, redactor(o), context);
        }
        return new LegacyTool(name, api, client, net, effects(o), redactor(o), context);
    }

    private static String missing(String id, ApiDescription api, String spec) {
        TreeSet<String> ids = new TreeSet<>();
        api.operations().forEach(op -> ids.add(op.id()));
        List<String> some = ids.stream().limit(LISTED_ON_MISS).toList();
        return "operation: "
                + id
                + " is not an operation of "
                + spec
                + (some.isEmpty() ? "" : " (it has " + String.join(", ", some) + (ids.size() > some.size() ? ", …" : "") + ")");
    }

    private static Path safeDir(Path baseDir, String dir) {
        try {
            return SafePaths.inside(baseDir, dir);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("save_dir: " + e.getMessage(), e);
        }
    }

    /** The address the calls go to: the declaration's, else one the description names. */
    private static HttpUrl baseUrl(Options o, ApiDescription api, HttpUrl specUrl) {
        if (o.has("base_url")) return HttpUrl.parse(o.get("base_url"));
        List<String> servers = api.servers();
        String chosen;
        if (o.has("server")) {
            chosen = o.get("server");
            if (!servers.contains(chosen))
                throw new IllegalArgumentException(
                        "server: " + chosen + " is not one of the description's servers (" + String.join(", ", servers) + ")");
        } else if (servers.isEmpty()) {
            throw new IllegalArgumentException(
                    "the description names no server, so give base_url: (the address the calls go to)");
        } else {
            chosen = servers.get(0);
        }
        HttpUrl url = HttpUrl.parse(chosen);
        if (url == null && specUrl != null) url = specUrl.resolve(chosen);
        if (url == null)
            throw new IllegalArgumentException(
                    "the server " + chosen + " is not an address on its own; give base_url: (the address the calls go to)");
        return url;
    }

    /** Fetches a remote description or reference through the guarded layer, or refuses. */
    private SpecLoader.Fetcher fetcher(NetOptions net, String specHost, EffectContext context) {
        return url -> {
            NetPolicy policy = new NetPolicy(net.rules(), NetPolicy.Resolver.SYSTEM, specHost);
            HttpSupport http =
                    new HttpSupport(
                            policy,
                            context,
                            net.timeout(),
                            SpecLoader.MAX_DOCUMENT_BYTES,
                            net.retries(),
                            sslSocketFactory,
                            trust);
            try {
                HttpSupport.Reply reply = http.send(new Request.Builder().url(url).build(), HttpSupport.Retry.ALL);
                if (!reply.success())
                    throw new IOException(url.host() + " answered HTTP " + reply.status());
                if (reply.truncated()) throw new IOException(url.host() + " sent a document larger than the limit");
                return reply.body();
            } catch (ToolRefusal refused) {
                throw new IOException(refused.getMessage(), refused);
            }
        };
    }
}
