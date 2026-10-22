package io.github.llm4j.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Represents a response from an LLM. This class is immutable and thread-safe. */
public final class LLMResponse {

    public enum FinishReason {
        STOP("stop"),
        LENGTH("length"),
        CONTENT_FILTER("content_filter"),
        TOOL_CALLS("tool_calls"),
        ERROR("error"),
        UNKNOWN("unknown");

        private final String value;

        FinishReason(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        /**
         * Provider-specific values, so the same stop reads the same whichever provider answered.
         */
        private static final java.util.Map<String, FinishReason> SYNONYMS = synonyms();

        private static java.util.Map<String, FinishReason> synonyms() {
            java.util.Map<String, FinishReason> m = new java.util.HashMap<>();
            for (String v :
                    new String[] {
                        "end_turn", "stop_sequence", "pause_turn", "finish_reason_stop", "eos"
                    }) m.put(v, STOP);
            for (String v : new String[] {"max_tokens", "model_context_window_exceeded"})
                m.put(v, LENGTH);
            for (String v :
                    new String[] {
                        "refusal",
                        "safety",
                        "recitation",
                        "blocklist",
                        "prohibited_content",
                        "spii",
                        "image_safety"
                    }) m.put(v, CONTENT_FILTER);
            for (String v : new String[] {"tool_use", "function_call"}) m.put(v, TOOL_CALLS);
            for (String v : new String[] {"malformed_function_call"}) m.put(v, ERROR);
            return java.util.Map.copyOf(m);
        }

        /**
         * The reason for a value: this enum's own values, or a provider's name for the same thing
         * (Gemini's {@code MAX_TOKENS}, Anthropic's {@code end_turn}, …), case-insensitive; else
         * {@link #UNKNOWN}.
         */
        public static FinishReason fromValue(String value) {
            if (value == null) {
                return UNKNOWN;
            }
            for (FinishReason reason : values()) {
                if (reason.value.equalsIgnoreCase(value)) {
                    return reason;
                }
            }
            return SYNONYMS.getOrDefault(value.toLowerCase(java.util.Locale.ROOT), UNKNOWN);
        }
    }

    private final String content;
    private final String model;
    private final TokenUsage tokenUsage;
    private final FinishReason finishReason;
    private final Map<String, Object> metadata;
    private final java.util.List<ToolCall> toolCalls;
    private final Map<String, Object> providerData;
    private final String reasoning;
    private final ToolCallDelta toolCallDelta;
    private final java.util.List<io.github.llm4j.grounding.Citation> citations;
    private final io.github.llm4j.grounding.SearchUsage searches;

    private LLMResponse(Builder builder) {
        this.content = builder.content;
        this.model = builder.model;
        this.tokenUsage = builder.tokenUsage;
        this.finishReason =
                builder.finishReason != null ? builder.finishReason : FinishReason.UNKNOWN;
        this.toolCalls =
                builder.toolCalls == null
                        ? java.util.List.of()
                        : java.util.List.copyOf(builder.toolCalls);
        this.providerData =
                builder.providerData == null ? Map.of() : Map.copyOf(builder.providerData);
        this.reasoning = builder.reasoning;
        this.toolCallDelta = builder.toolCallDelta;
        this.citations =
                builder.citations == null
                        ? java.util.List.of()
                        : java.util.List.copyOf(builder.citations);
        this.searches = builder.searches;
        this.metadata =
                builder.metadata != null
                        ? Collections.unmodifiableMap(new HashMap<>(builder.metadata))
                        : Collections.emptyMap();
    }

    public String getContent() {
        return content;
    }

    public String getModel() {
        return model;
    }

    public TokenUsage getTokenUsage() {
        return tokenUsage;
    }

    public FinishReason getFinishReason() {
        return finishReason;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /** The tools the model asked for (empty when it answered in text). */
    public java.util.List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /**
     * What the provider needs sent back unchanged with the tool results; copy it onto the assistant
     * {@link Message}.
     */
    public Map<String, Object> getProviderData() {
        return providerData;
    }

    /**
     * On a streamed chunk: a piece of the model's reasoning (null on every other chunk, and on
     * every chunk unless the request asked for it with {@link LLMRequest.Builder#reasoning}). It is
     * never part of {@link #getContent()}, which stays the answer only, so a consumer that joins
     * the content of the chunks gets the same answer with or without reasoning. What it holds
     * depends on the model: a summary of its thinking where the provider offers one, otherwise
     * nothing.
     */
    public String getReasoning() {
        return reasoning;
    }

    /**
     * On a streamed chunk: a piece of a tool call being written (null on every other chunk). See
     * {@link ToolCallDelta}.
     */
    public ToolCallDelta getToolCallDelta() {
        return toolCallDelta;
    }

    /**
     * The sources a grounded answer is attributed to, as the vendor returned them (empty when the
     * request was not grounded or the vendor named none; never null). The metadata key {@code
     * grounding.citation_basis} says whether they are sources the answer {@code cited} or sources
     * the search {@code consulted}. A streamed call reports them in the final chunk.
     */
    public java.util.List<io.github.llm4j.grounding.Citation> getCitations() {
        return citations;
    }

    /**
     * The web searches the call ran, as the vendor reports them; null when none ran. A streamed
     * call reports them in the final chunk.
     */
    public io.github.llm4j.grounding.SearchUsage getSearches() {
        return searches;
    }

    /** A builder holding everything of this response, so a copy never drops a field. */
    public Builder toBuilder() {
        Builder b =
                builder()
                        .content(content)
                        .model(model)
                        .tokenUsage(tokenUsage)
                        .finishReason(finishReason)
                        .metadata(metadata)
                        .toolCalls(toolCalls)
                        .providerData(providerData)
                        .reasoning(reasoning)
                        .toolCallDelta(toolCallDelta)
                        .citations(citations)
                        .searches(searches);
        return b;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LLMResponse that = (LLMResponse) o;
        return Objects.equals(content, that.content)
                && Objects.equals(model, that.model)
                && Objects.equals(tokenUsage, that.tokenUsage)
                && finishReason == that.finishReason
                && Objects.equals(metadata, that.metadata)
                && Objects.equals(toolCalls, that.toolCalls)
                && Objects.equals(providerData, that.providerData)
                && Objects.equals(reasoning, that.reasoning)
                && Objects.equals(toolCallDelta, that.toolCallDelta)
                && Objects.equals(citations, that.citations)
                && Objects.equals(searches, that.searches);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                content,
                model,
                tokenUsage,
                finishReason,
                metadata,
                toolCalls,
                providerData,
                reasoning,
                toolCallDelta,
                citations,
                searches);
    }

    @Override
    public String toString() {
        return "LLMResponse{"
                + "content='"
                + content
                + '\''
                + ", model='"
                + model
                + '\''
                + ", tokenUsage="
                + tokenUsage
                + ", finishReason="
                + finishReason
                + ", metadata="
                + metadata
                + '}';
    }

    /** Represents token usage information for a request/response. */
    public static final class TokenUsage {
        private final int promptTokens;
        private final int completionTokens;
        private final int totalTokens;

        public TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }

        public int getPromptTokens() {
            return promptTokens;
        }

        public int getCompletionTokens() {
            return completionTokens;
        }

        public int getTotalTokens() {
            return totalTokens;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TokenUsage that = (TokenUsage) o;
            return promptTokens == that.promptTokens
                    && completionTokens == that.completionTokens
                    && totalTokens == that.totalTokens;
        }

        @Override
        public int hashCode() {
            return Objects.hash(promptTokens, completionTokens, totalTokens);
        }

        @Override
        public String toString() {
            return "TokenUsage{"
                    + "promptTokens="
                    + promptTokens
                    + ", completionTokens="
                    + completionTokens
                    + ", totalTokens="
                    + totalTokens
                    + '}';
        }
    }

    public static final class Builder {
        private String content;
        private String model;
        private TokenUsage tokenUsage;
        private FinishReason finishReason;
        private Map<String, Object> metadata;
        private java.util.List<ToolCall> toolCalls;
        private Map<String, Object> providerData;
        private String reasoning;
        private ToolCallDelta toolCallDelta;
        private java.util.List<io.github.llm4j.grounding.Citation> citations;
        private io.github.llm4j.grounding.SearchUsage searches;

        private Builder() {}

        /** The sources a grounded answer is attributed to. */
        public Builder citations(java.util.List<io.github.llm4j.grounding.Citation> citations) {
            this.citations = citations;
            return this;
        }

        /** The web searches the call ran, or null. */
        public Builder searches(io.github.llm4j.grounding.SearchUsage searches) {
            this.searches = searches;
            return this;
        }

        public Builder reasoning(String reasoning) {
            this.reasoning = reasoning;
            return this;
        }

        public Builder toolCallDelta(ToolCallDelta toolCallDelta) {
            this.toolCallDelta = toolCallDelta;
            return this;
        }

        public Builder toolCalls(java.util.List<ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
            return this;
        }

        public Builder providerData(Map<String, Object> providerData) {
            this.providerData = providerData;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder tokenUsage(TokenUsage tokenUsage) {
            this.tokenUsage = tokenUsage;
            return this;
        }

        public Builder tokenUsage(int promptTokens, int completionTokens, int totalTokens) {
            this.tokenUsage = new TokenUsage(promptTokens, completionTokens, totalTokens);
            return this;
        }

        public Builder finishReason(FinishReason finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        public Builder finishReason(String finishReason) {
            this.finishReason = FinishReason.fromValue(finishReason);
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder addMetadata(String key, Object value) {
            if (this.metadata == null) {
                this.metadata = new HashMap<>();
            }
            this.metadata.put(key, value);
            return this;
        }

        public LLMResponse build() {
            return new LLMResponse(this);
        }
    }
}
