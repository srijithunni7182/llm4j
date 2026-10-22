package io.github.llm4j.provider.openai;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.LLMProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The vendors served by {@link OpenAiCompatibleProvider} and {@link OpenAiResponsesProvider}, as
 * configuration layers (a Loom script, a Spring starter) need to know them: the kind a user writes
 * ({@code use: kimi}), the model-name prefix, the environment variables for the key and the base
 * URL, the hosts a stored key may go to, and how a bare model name ({@code gpt-5-mini}, {@code
 * grok-4}) is told apart from other vendors' names. It is data over the dialects, so a layer asks
 * here instead of naming vendors.
 */
public enum Vendor {
    /** OpenAI (chat completions; {@code api: responses} selects the Responses API). */
    OPENAI("openai", Dialect.OPENAI, ResponsesDialect.OPENAI, false),
    /** xAI (Grok); {@code api: responses} selects the Responses API, which has web search. */
    XAI("xai", Dialect.XAI, ResponsesDialect.XAI, false),
    /** Moonshot's Kimi. Preview until its fixtures are recorded from the real API. */
    KIMI("kimi", Dialect.KIMI, null, true),
    /**
     * Alibaba's Qwen through DashScope. Preview until its fixtures are recorded from the real API.
     */
    QWEN("qwen", Dialect.QWEN, null, true),
    /** Perplexity's Agent API (Responses schema). */
    PERPLEXITY("perplexity", null, ResponsesDialect.PERPLEXITY, false),
    /** Perplexity's Orchestrator API (private preview). */
    PERPLEXITY_ROUTER("perplexity_router", Dialect.PERPLEXITY_ROUTER, null, true),
    /** Any OpenAI-style server at a base URL. */
    OPENAI_COMPATIBLE("openai_compatible", Dialect.GENERIC, null, false);

    private static final Pattern OPENAI_BARE =
            Pattern.compile("^(?:gpt-(?!oss)|chatgpt-|o[134](?:-.*|$)).*");
    private static final Set<String> GENERIC_OPTIONS = Set.of("header", "query");

    private final String kind;
    private final Dialect chat;
    private final ResponsesDialect responses;
    private final boolean preview;

    Vendor(String kind, Dialect chat, ResponsesDialect responses, boolean preview) {
        this.kind = kind;
        this.chat = chat;
        this.responses = responses;
        this.preview = preview;
    }

    /** The kind written in a script ({@code use: …}). */
    public String kind() {
        return kind;
    }

    /**
     * How the vendor is named to people ("OpenAI", "Moonshot (Kimi)"), for audit reports and
     * messages.
     */
    public String displayName() {
        return switch (this) {
            case OPENAI -> "OpenAI";
            case XAI -> "xAI (Grok)";
            case KIMI -> "Moonshot (Kimi)";
            case QWEN -> "Alibaba (Qwen via DashScope)";
            case PERPLEXITY -> "Perplexity (Agent API)";
            case PERPLEXITY_ROUTER -> "Perplexity (Orchestrator API)";
            case OPENAI_COMPATIBLE -> "an OpenAI-compatible server";
        };
    }

    /**
     * The model-name prefix that selects this vendor: {@code openai/}, {@code perplexity/} (none
     * for the generic kind).
     */
    public String prefix() {
        return this == OPENAI_COMPATIBLE ? null : kind + "/";
    }

    /** The environment variable that holds the key. */
    public String keyEnv() {
        return chat != null ? chat.keyEnv() : responses.keyEnv();
    }

    /** The environment variable that overrides the base URL. */
    public String baseUrlEnv() {
        return chat != null ? chat.baseUrlEnv() : responses.baseUrlEnv();
    }

    /**
     * The hosts a stored secret for this vendor may be sent to (empty for the generic kind, whose
     * host is the user's).
     */
    public List<String> hosts() {
        return chat != null ? chat.hosts() : responses.hosts();
    }

    /**
     * Whether the vendor is documented as a preview: its fixtures are not yet recorded from the
     * real API, or its API is in private preview.
     */
    public boolean preview() {
        return preview;
    }

    /** Whether a call without a key is allowed (a local server). */
    public boolean keyOptional() {
        return this == OPENAI_COMPATIBLE;
    }

    /**
     * The hosts a secret called {@code name} should be bound to: the vendor's, when {@code name} is
     * that vendor's key variable; else none.
     */
    public static List<String> hostsOfKey(String name) {
        return Stream.of(values())
                .filter(v -> v.keyEnv() != null && v.keyEnv().equals(name))
                .findFirst()
                .map(Vendor::hosts)
                .orElse(List.of());
    }

    /** All kinds, as written in scripts. */
    public static Set<String> kinds() {
        return Stream.of(values()).map(Vendor::kind).collect(Collectors.toUnmodifiableSet());
    }

    /** The vendor of a kind, if it is one of ours. */
    public static Optional<Vendor> ofKind(String kind) {
        return Stream.of(values()).filter(v -> v.kind.equals(kind)).findFirst();
    }

    /** A model name resolved to a vendor and the name to send it. */
    public record Resolved(Vendor vendor, String model) {}

    /**
     * Resolves a model name: an explicit prefix always works ({@code openai/gpt-5-mini}, {@code
     * perplexity/openai/gpt-5.6-sol}); a bare name only when no other vendor could own it ({@code
     * gpt-*}, {@code o1}/{@code o3}/{@code o4}, {@code chatgpt-*} → OpenAI; {@code grok-*} → xAI;
     * {@code kimi-*}, {@code moonshot-*} → Kimi). {@code qwen3} stays unresolved because it usually
     * means a local Ollama model, and so does {@code gpt-oss}.
     */
    public static Optional<Resolved> ofModel(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String m = name.toLowerCase(Locale.ROOT);
        for (Vendor v : values()) {
            if (v.prefix() != null
                    && m.startsWith(v.prefix())
                    && m.length() > v.prefix().length()) {
                return Optional.of(new Resolved(v, name.substring(v.prefix().length())));
            }
        }
        if (OPENAI_BARE.matcher(m).matches()) return Optional.of(new Resolved(OPENAI, name));
        if (m.startsWith("grok-")) return Optional.of(new Resolved(XAI, name));
        if (m.startsWith("kimi-") || m.startsWith("moonshot-"))
            return Optional.of(new Resolved(KIMI, name));
        return Optional.empty();
    }

    /**
     * The provider for this vendor. {@code options} are those a script can give: {@code api}
     * ({@code chat} or {@code responses}) for OpenAI and xAI, and {@code header} and {@code query}
     * ({@code name=value&…}) for the generic kind. Anything else is refused by name.
     */
    public LLMProvider create(LLMConfig config, Map<String, String> options) {
        Map<String, String> o = new LinkedHashMap<>(options == null ? Map.of() : options);
        String api = o.remove("api");
        if (api != null && this != OPENAI && this != XAI)
            throw new IllegalArgumentException(
                    "the option api is only for use: openai or xai (chat or responses)");
        if (api != null && !api.equals("chat") && !api.equals("responses")) {
            throw new IllegalArgumentException("api must be chat or responses, got " + api);
        }
        Dialect dialect = chat;
        if (this == OPENAI_COMPATIBLE) {
            String header = o.remove("header");
            String query = o.remove("query");
            if (header != null) dialect = dialect.withAuthHeader(header, "");
            if (query != null) dialect = dialect.withQueryParams(parseQuery(query));
        }
        if (!o.isEmpty()) {
            String bad = o.keySet().iterator().next();
            throw new IllegalArgumentException(
                    "unknown option "
                            + bad
                            + " for use: "
                            + kind
                            + (this == OPENAI_COMPATIBLE
                                    ? "; use base_url, api_key, "
                                            + String.join(", ", GENERIC_OPTIONS)
                                    : this == OPENAI || this == XAI
                                            ? "; use base_url, api_key, api"
                                            : "; use base_url, api_key"));
        }
        if (responses != null && (chat == null || "responses".equals(api))) {
            return new OpenAiResponsesProvider(responses, config);
        }
        return new OpenAiCompatibleProvider(dialect, config);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isBlank()) continue;
            int eq = pair.indexOf('=');
            if (eq <= 0)
                throw new IllegalArgumentException(
                        "query must be name=value pairs joined with &, got " + pair);
            out.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return out;
    }
}
