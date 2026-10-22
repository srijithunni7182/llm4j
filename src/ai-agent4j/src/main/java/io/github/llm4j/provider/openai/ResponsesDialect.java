package io.github.llm4j.provider.openai;

import io.github.llm4j.grounding.Recency;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * How a vendor's Responses-schema API differs from the plain protocol, as {@link Dialect} does for
 * chat completions: where to call, which hosts a secret may go to, how a model name is sent
 * (Perplexity names a preset instead of a model), which output items must be sent back with a tool
 * result, and what a model refuses. Data, not subclasses: {@link OpenAiResponsesProvider} names no
 * vendor.
 *
 * <p>The built-ins are {@link #OPENAI} (OpenAI's own Responses API) and {@link #PERPLEXITY} (the
 * Agent API, {@code POST /v1/agent}).
 */
public final class ResponsesDialect {

    private final String name;
    private final String defaultBaseUrl;
    private final String path;
    private final String maxTokensField;
    private final boolean keyRequired;
    private final String keyEnv;
    private final String baseUrlEnv;
    private final List<String> hosts;
    private final Function<String, ModelTraits> traits;
    private final Function<String, Integer> requiredMaxTokens;
    private final Map<String, String> modelFieldByPrefix;
    private final Set<String> replayTypes;
    private final boolean reasoningSummary;
    private final Map<String, Object> extraRequest;
    private final ResponsesGrounding grounding;

    private ResponsesDialect(Builder b) {
        this.name = Objects.requireNonNull(b.name, "name cannot be null");
        this.defaultBaseUrl = b.defaultBaseUrl;
        this.path = b.path;
        this.maxTokensField = b.maxTokensField;
        this.keyRequired = b.keyRequired;
        this.keyEnv = b.keyEnv;
        this.baseUrlEnv = b.baseUrlEnv;
        this.hosts = List.copyOf(b.hosts);
        this.traits = b.traits;
        this.requiredMaxTokens = b.requiredMaxTokens;
        this.modelFieldByPrefix = Map.copyOf(b.modelFieldByPrefix);
        this.replayTypes = Set.copyOf(b.replayTypes);
        this.reasoningSummary = b.reasoningSummary;
        this.extraRequest = Map.copyOf(b.extraRequest);
        this.grounding = b.grounding;
    }

    /**
     * The provider name ({@code openai}, {@code perplexity}) as {@code getProviderName()} reports
     * it.
     */
    public String name() {
        return name;
    }

    /** Where the vendor's API lives. */
    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    /** The path appended to the base URL, for example {@code /responses} or {@code /agent}. */
    public String path() {
        return path;
    }

    /** The request field that limits the output, {@code max_output_tokens}. */
    public String maxTokensField() {
        return maxTokensField;
    }

    /** Whether a call without a key is refused. */
    public boolean keyRequired() {
        return keyRequired;
    }

    /** The environment variable that holds the key. */
    public String keyEnv() {
        return keyEnv;
    }

    /** The environment variable that overrides the base URL. */
    public String baseUrlEnv() {
        return baseUrlEnv;
    }

    /** The hosts a stored secret for this vendor may be sent to. */
    public List<String> hosts() {
        return hosts;
    }

    /**
     * What the named model accepts; {@link ModelTraits#DEFAULT} for a name nothing is known about.
     */
    public ModelTraits traits(String model) {
        ModelTraits t = traits.apply(model);
        return t == null ? ModelTraits.DEFAULT : t;
    }

    /**
     * An output limit the vendor insists on for this model (sent when the request names none), or
     * null.
     */
    public Integer requiredMaxTokens(String model) {
        return requiredMaxTokens.apply(model);
    }

    /**
     * The request field a model name is sent in: {@code model}, or another field when the name
     * starts with a known prefix ({@code preset:}).
     */
    public String modelField(String model) {
        for (Map.Entry<String, String> e : modelFieldByPrefix.entrySet()) {
            if (model.startsWith(e.getKey())) return e.getValue();
        }
        return "model";
    }

    /** The model name as the field wants it, without the prefix that chose the field. */
    public String modelValue(String model) {
        for (Map.Entry<String, String> e : modelFieldByPrefix.entrySet()) {
            if (model.startsWith(e.getKey())) return model.substring(e.getKey().length());
        }
        return model;
    }

    /**
     * The output item types that are sent back with a tool result (reasoning and function calls,
     * say), as the vendor returned them.
     */
    public Set<String> replayTypes() {
        return replayTypes;
    }

    /**
     * Whether a streamed call that asks to see reasoning requests summaries ({@code
     * reasoning.summary}).
     */
    public boolean reasoningSummary() {
        return reasoningSummary;
    }

    /** Fields added to every request body. */
    public Map<String, Object> extraRequest() {
        return extraRequest;
    }

    /** How this vendor does web-search grounding, or null when it offers none. */
    public ResponsesGrounding grounding() {
        return grounding;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    @Override
    public String toString() {
        return "ResponsesDialect{" + name + "}";
    }

    /** OpenAI's Responses API. */
    public static final ResponsesDialect OPENAI =
            builder("openai")
                    .defaultBaseUrl("https://api.openai.com/v1")
                    .path("/responses")
                    .keyEnv("OPENAI_API_KEY")
                    .baseUrlEnv("OPENAI_BASE_URL")
                    .hosts(List.of("api.openai.com"))
                    .traits(ModelTraits::openai)
                    .replayTypes(Set.of("message", "reasoning", "function_call", "web_search_call"))
                    .reasoningSummary(true)
                    .grounding(
                            ResponsesGrounding.builder("web_search")
                                    .allowDomains("filters.allowed_domains", 100)
                                    .blockDomains("filters.blocked_domains", 100)
                                    .country(
                                            "user_location.country",
                                            Map.of("user_location.type", "approximate"))
                                    .maxSearches("max_tool_calls")
                                    .include(List.of("web_search_call.action.sources"))
                                    .citedSources()
                                    .callItems("web_search_call")
                                    .build())
                    .build();

    /** xAI's Responses API (the chat-completions API of the same vendor is {@link Dialect#XAI}). */
    public static final ResponsesDialect XAI =
            builder("xai")
                    .defaultBaseUrl("https://api.x.ai/v1")
                    .path("/responses")
                    .keyEnv("XAI_API_KEY")
                    .baseUrlEnv("XAI_BASE_URL")
                    .hosts(List.of("api.x.ai"))
                    .traits(ModelTraits::xai)
                    .grounding(
                            ResponsesGrounding.builder("web_search")
                                    .allowDomains("filters.allowed_domains", 5)
                                    .blockDomains("filters.excluded_domains", 5)
                                    .citedSources()
                                    .callItems("web_search_call")
                                    .countPath(
                                            "usage.server_side_tool_usage_details.web_search_calls")
                                    .build())
                    .build();

    /**
     * Perplexity's Agent API: one key for OpenAI, Anthropic, Google and xAI models, with opt-in web
     * grounding.
     */
    public static final ResponsesDialect PERPLEXITY =
            builder("perplexity")
                    .defaultBaseUrl("https://api.perplexity.ai/v1")
                    .path("/agent")
                    .keyEnv("PERPLEXITY_API_KEY")
                    .baseUrlEnv("PERPLEXITY_BASE_URL")
                    .hosts(List.of("api.perplexity.ai"))
                    .traits(ModelTraits::perplexity)
                    .requiredMaxTokens(m -> m.startsWith("anthropic/") ? 8_000 : null)
                    .modelFieldByPrefix(Map.of("preset:", "preset"))
                    .replayTypes(Set.of("message", "function_call"))
                    .grounding(
                            ResponsesGrounding.builder("web_search")
                                    .allowDomains("filters.search_domain_filter", 20)
                                    .blockDomainsWithPrefix("-", 20)
                                    .recency(
                                            "filters.search_recency_filter",
                                            Map.of(
                                                    Recency.DAY,
                                                    "day",
                                                    Recency.WEEK,
                                                    "week",
                                                    Recency.MONTH,
                                                    "month",
                                                    Recency.YEAR,
                                                    "year"))
                                    .country("user_location.country", Map.of())
                                    .consultedSources("search_results")
                                    .countPath("usage.tool_calls_details.search_web.invocation")
                                    .costPath("usage.tool_calls_details.search_web.cost_usd")
                                    .build())
                    .build();

    /** Builds a {@link ResponsesDialect}. */
    public static final class Builder {
        private final String name;
        private String defaultBaseUrl;
        private String path = "/responses";
        private String maxTokensField = "max_output_tokens";
        private boolean keyRequired = true;
        private String keyEnv;
        private String baseUrlEnv;
        private List<String> hosts = new ArrayList<>();
        private Function<String, ModelTraits> traits = m -> ModelTraits.DEFAULT;
        private Function<String, Integer> requiredMaxTokens = m -> null;
        private Map<String, String> modelFieldByPrefix = Map.of();
        private Set<String> replayTypes = Set.of("message", "reasoning", "function_call");
        private boolean reasoningSummary;
        private Map<String, Object> extraRequest = Map.of();
        private ResponsesGrounding grounding;

        private Builder(String name) {
            this.name = name;
        }

        public Builder defaultBaseUrl(String url) {
            this.defaultBaseUrl = url;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder maxTokensField(String field) {
            this.maxTokensField = field;
            return this;
        }

        public Builder keyRequired(boolean keyRequired) {
            this.keyRequired = keyRequired;
            return this;
        }

        public Builder keyEnv(String env) {
            this.keyEnv = env;
            return this;
        }

        public Builder baseUrlEnv(String env) {
            this.baseUrlEnv = env;
            return this;
        }

        public Builder hosts(List<String> hosts) {
            this.hosts = hosts == null ? new ArrayList<>() : new ArrayList<>(hosts);
            return this;
        }

        public Builder traits(Function<String, ModelTraits> traits) {
            this.traits = traits == null ? m -> ModelTraits.DEFAULT : traits;
            return this;
        }

        public Builder requiredMaxTokens(Function<String, Integer> required) {
            this.requiredMaxTokens = required == null ? m -> null : required;
            return this;
        }

        public Builder modelFieldByPrefix(Map<String, String> map) {
            this.modelFieldByPrefix = map == null ? Map.of() : new LinkedHashMap<>(map);
            return this;
        }

        public Builder replayTypes(Set<String> types) {
            this.replayTypes = types == null ? Set.of() : types;
            return this;
        }

        public Builder reasoningSummary(boolean reasoningSummary) {
            this.reasoningSummary = reasoningSummary;
            return this;
        }

        public Builder extraRequest(Map<String, Object> fields) {
            this.extraRequest = fields == null ? Map.of() : new LinkedHashMap<>(fields);
            return this;
        }

        /** How the vendor does web-search grounding; leave unset for a vendor that has none. */
        public Builder grounding(ResponsesGrounding grounding) {
            this.grounding = grounding;
            return this;
        }

        public ResponsesDialect build() {
            return new ResponsesDialect(this);
        }
    }
}
