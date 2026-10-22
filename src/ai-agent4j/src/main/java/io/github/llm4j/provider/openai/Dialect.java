package io.github.llm4j.provider.openai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * How a vendor's chat-completions API differs from the plain protocol: where to call, how to
 * authenticate, which field limits the output, where reasoning text comes back, what static fields
 * every request carries, and which hosts a secret may be sent to. It is data, not a subclass, so a
 * new vendor is a constant (or a {@link #builder}) and a recorded fixture, and {@link
 * OpenAiCompatibleProvider} never names a vendor. Differences between one vendor's models are in
 * {@link ModelTraits}.
 *
 * <p>The built-ins are {@link #OPENAI}, {@link #XAI}, {@link #KIMI}, {@link #QWEN}, {@link
 * #PERPLEXITY_ROUTER} and {@link #GENERIC}, which has no base URL of its own: give it one (and, for
 * gateways that want it, another header name or fixed query parameters) to reach DeepSeek, Groq,
 * Together, vLLM, LM Studio and the like.
 */
public final class Dialect {

    private final String name;
    private final String defaultBaseUrl;
    private final String chatPath;
    private final String maxTokensField;
    private final String authHeader;
    private final String authScheme;
    private final boolean keyRequired;
    private final boolean toolCalling;
    private final boolean streamUsage;
    private final boolean thinkTags;
    private final List<String> reasoningFields;
    private final Map<String, Object> extraRequest;
    private final Map<String, String> queryParams;
    private final String keyEnv;
    private final String baseUrlEnv;
    private final List<String> hosts;
    private final Function<String, ModelTraits> traits;
    private final String fallbackModel;

    private Dialect(Builder b) {
        this.name = Objects.requireNonNull(b.name, "name cannot be null");
        this.defaultBaseUrl = b.defaultBaseUrl;
        this.chatPath = b.chatPath;
        this.maxTokensField = b.maxTokensField;
        this.authHeader = b.authHeader;
        this.authScheme = b.authScheme;
        this.keyRequired = b.keyRequired;
        this.toolCalling = b.toolCalling;
        this.streamUsage = b.streamUsage;
        this.thinkTags = b.thinkTags;
        this.reasoningFields = List.copyOf(b.reasoningFields);
        this.extraRequest = Map.copyOf(b.extraRequest);
        this.queryParams = Map.copyOf(b.queryParams);
        this.keyEnv = b.keyEnv;
        this.baseUrlEnv = b.baseUrlEnv;
        this.hosts = List.copyOf(b.hosts);
        this.traits = b.traits;
        this.fallbackModel = b.fallbackModel;
    }

    /**
     * The provider name ({@code openai}, {@code xai}, …) as {@link
     * io.github.llm4j.provider.LLMProvider#getProviderName()} reports it.
     */
    public String name() {
        return name;
    }

    /**
     * Where the vendor's API lives, or null when there is none (the generic dialect needs a
     * configured base URL).
     */
    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    /**
     * The path appended to the base URL for a chat request, for example {@code /chat/completions}.
     */
    public String chatPath() {
        return chatPath;
    }

    /**
     * The request field that limits the output: {@code max_tokens}, or {@code
     * max_completion_tokens} where the vendor has moved to it.
     */
    public String maxTokensField() {
        return maxTokensField;
    }

    /** The header that carries the key, {@code Authorization} by default. */
    public String authHeader() {
        return authHeader;
    }

    /**
     * What goes before the key in that header: {@code Bearer } by default, empty for a bare key.
     */
    public String authScheme() {
        return authScheme;
    }

    /**
     * Whether a call without a key is refused (true for the hosted vendors; false for gateways such
     * as a local vLLM).
     */
    public boolean keyRequired() {
        return keyRequired;
    }

    /** Whether the vendor's models take native tool definitions and return tool calls. */
    public boolean toolCalling() {
        return toolCalling;
    }

    /** Whether a streamed request asks for token usage ({@code stream_options.include_usage}). */
    public boolean streamUsage() {
        return streamUsage;
    }

    /**
     * Whether models may write their reasoning into the answer as a leading {@code <think>} block,
     * which is then removed.
     */
    public boolean thinkTags() {
        return thinkTags;
    }

    /**
     * The response fields that hold the model's reasoning, in order of preference ({@code
     * reasoning_content}, …). Never part of the answer.
     */
    public List<String> reasoningFields() {
        return reasoningFields;
    }

    /**
     * Fields added to every request body, for example a vendor option that has no portable
     * equivalent.
     */
    public Map<String, Object> extraRequest() {
        return extraRequest;
    }

    /** Query parameters added to every request URL (an API version, say). */
    public Map<String, String> queryParams() {
        return queryParams;
    }

    /**
     * The environment variable that holds the API key, for configuration layers that read one (for
     * example {@code OPENAI_API_KEY}).
     */
    public String keyEnv() {
        return keyEnv;
    }

    /** The environment variable that overrides the base URL. */
    public String baseUrlEnv() {
        return baseUrlEnv;
    }

    /**
     * The hosts a stored secret for this vendor may be sent to (the host binding offered when the
     * secret is created).
     */
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
     * The model used when neither the request nor the configuration names one; null when there is
     * no sensible default.
     */
    public String fallbackModel() {
        return fallbackModel;
    }

    /**
     * A builder holding everything of this dialect, to derive a variant (another base URL, header
     * or query parameters).
     */
    public Builder toBuilder() {
        return new Builder(name)
                .defaultBaseUrl(defaultBaseUrl)
                .chatPath(chatPath)
                .maxTokensField(maxTokensField)
                .authHeader(authHeader, authScheme)
                .keyRequired(keyRequired)
                .toolCalling(toolCalling)
                .streamUsage(streamUsage)
                .thinkTags(thinkTags)
                .reasoningFields(reasoningFields)
                .extraRequest(extraRequest)
                .queryParams(queryParams)
                .keyEnv(keyEnv)
                .baseUrlEnv(baseUrlEnv)
                .hosts(hosts)
                .traits(traits)
                .fallbackModel(fallbackModel);
    }

    /**
     * The generic dialect with the key sent in a header of another name, as some gateways expect
     * ({@code api-key}, {@code x-api-key}).
     */
    public Dialect withAuthHeader(String header, String scheme) {
        return toBuilder().authHeader(header, scheme).build();
    }

    /**
     * This dialect with fixed query parameters on every request, for example {@code api-version}.
     */
    public Dialect withQueryParams(Map<String, String> params) {
        return toBuilder().queryParams(params).build();
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    @Override
    public String toString() {
        return "Dialect{" + name + "}";
    }

    // ── the built-in vendors ────────────────────────────────────────────────────────────────

    /** OpenAI chat completions. */
    public static final Dialect OPENAI =
            builder("openai")
                    .defaultBaseUrl("https://api.openai.com/v1")
                    .maxTokensField("max_completion_tokens")
                    .keyEnv("OPENAI_API_KEY")
                    .baseUrlEnv("OPENAI_BASE_URL")
                    .hosts(List.of("api.openai.com"))
                    .traits(ModelTraits::openai)
                    .build();

    /** xAI (Grok) chat completions. */
    public static final Dialect XAI =
            builder("xai")
                    .defaultBaseUrl("https://api.x.ai/v1")
                    .maxTokensField("max_completion_tokens")
                    .keyEnv("XAI_API_KEY")
                    .baseUrlEnv("XAI_BASE_URL")
                    .hosts(List.of("api.x.ai"))
                    .traits(ModelTraits::xai)
                    .build();

    /** Moonshot's Kimi chat completions (preview: documentation-derived fixtures only). */
    public static final Dialect KIMI =
            builder("kimi")
                    .defaultBaseUrl("https://api.moonshot.ai/v1")
                    .maxTokensField("max_completion_tokens")
                    .keyEnv("MOONSHOT_API_KEY")
                    .baseUrlEnv("MOONSHOT_BASE_URL")
                    .hosts(List.of("api.moonshot.ai"))
                    .traits(ModelTraits::kimi)
                    .build();

    /**
     * Alibaba's Qwen through DashScope's compatible mode (preview: documentation-derived fixtures
     * only).
     */
    public static final Dialect QWEN =
            builder("qwen")
                    .defaultBaseUrl("https://dashscope-intl.aliyuncs.com/compatible-mode/v1")
                    .maxTokensField("max_tokens")
                    .keyEnv("DASHSCOPE_API_KEY")
                    .baseUrlEnv("DASHSCOPE_BASE_URL")
                    .hosts(List.of("dashscope-intl.aliyuncs.com", "dashscope.aliyuncs.com"))
                    .traits(ModelTraits::qwen)
                    .build();

    /**
     * Perplexity's Orchestrator API, an OpenAI chat-completions gateway in private preview
     * (documentation-derived, no live fixtures).
     */
    public static final Dialect PERPLEXITY_ROUTER =
            builder("perplexity-router")
                    .defaultBaseUrl("https://api.perplexity.ai/router/v1")
                    .maxTokensField("max_tokens")
                    .keyEnv("PERPLEXITY_API_KEY")
                    .baseUrlEnv("PERPLEXITY_ROUTER_BASE_URL")
                    .hosts(List.of("api.perplexity.ai"))
                    .build();

    /**
     * Any OpenAI-style server (DeepSeek, Groq, Together, vLLM, LM Studio, …): give it a base URL;
     * the key is optional.
     */
    public static final Dialect GENERIC =
            builder("openai-compatible")
                    .maxTokensField("max_tokens")
                    .keyRequired(false)
                    .thinkTags(true)
                    .reasoningFields(List.of("reasoning_content", "reasoning"))
                    .keyEnv("OPENAI_COMPATIBLE_API_KEY")
                    .baseUrlEnv("OPENAI_COMPATIBLE_BASE_URL")
                    .build();

    /** Builds a {@link Dialect}. */
    public static final class Builder {
        private final String name;
        private String defaultBaseUrl;
        private String chatPath = "/chat/completions";
        private String maxTokensField = "max_tokens";
        private String authHeader = "Authorization";
        private String authScheme = "Bearer ";
        private boolean keyRequired = true;
        private boolean toolCalling = true;
        private boolean streamUsage = true;
        private boolean thinkTags;
        private List<String> reasoningFields = List.of("reasoning_content");
        private Map<String, Object> extraRequest = Map.of();
        private Map<String, String> queryParams = Map.of();
        private String keyEnv;
        private String baseUrlEnv;
        private List<String> hosts = new ArrayList<>();
        private Function<String, ModelTraits> traits = m -> ModelTraits.DEFAULT;
        private String fallbackModel;

        private Builder(String name) {
            this.name = name;
        }

        public Builder defaultBaseUrl(String url) {
            this.defaultBaseUrl = url;
            return this;
        }

        public Builder chatPath(String path) {
            this.chatPath = path;
            return this;
        }

        public Builder maxTokensField(String field) {
            this.maxTokensField = field;
            return this;
        }

        /**
         * The header that carries the key and what precedes the key in it ({@code "Bearer "}, or
         * {@code ""} for a bare key).
         */
        public Builder authHeader(String header, String scheme) {
            this.authHeader = header;
            this.authScheme = scheme == null ? "" : scheme;
            return this;
        }

        public Builder keyRequired(boolean keyRequired) {
            this.keyRequired = keyRequired;
            return this;
        }

        public Builder toolCalling(boolean toolCalling) {
            this.toolCalling = toolCalling;
            return this;
        }

        public Builder streamUsage(boolean streamUsage) {
            this.streamUsage = streamUsage;
            return this;
        }

        public Builder thinkTags(boolean thinkTags) {
            this.thinkTags = thinkTags;
            return this;
        }

        public Builder reasoningFields(List<String> fields) {
            this.reasoningFields = fields == null ? List.of() : fields;
            return this;
        }

        public Builder extraRequest(Map<String, Object> fields) {
            this.extraRequest = fields == null ? Map.of() : new LinkedHashMap<>(fields);
            return this;
        }

        public Builder queryParams(Map<String, String> params) {
            this.queryParams = params == null ? Map.of() : new LinkedHashMap<>(params);
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

        public Builder fallbackModel(String model) {
            this.fallbackModel = model;
            return this;
        }

        public Dialect build() {
            return new Dialect(this);
        }
    }
}
