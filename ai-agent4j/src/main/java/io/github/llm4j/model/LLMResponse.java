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

        /** Provider-specific values, so the same stop reads the same whichever provider answered. */
        private static final java.util.Map<String, FinishReason> SYNONYMS = synonyms();

        private static java.util.Map<String, FinishReason> synonyms() {
            java.util.Map<String, FinishReason> m = new java.util.HashMap<>();
            for (String v : new String[] {"end_turn", "stop_sequence", "pause_turn", "finish_reason_stop", "eos"}) m.put(v, STOP);
            for (String v : new String[] {"max_tokens", "model_context_window_exceeded"}) m.put(v, LENGTH);
            for (String v : new String[] {"refusal", "safety", "recitation", "blocklist", "prohibited_content", "spii",
                    "image_safety"}) m.put(v, CONTENT_FILTER);
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

    private LLMResponse(Builder builder) {
        this.content = builder.content;
        this.model = builder.model;
        this.tokenUsage = builder.tokenUsage;
        this.finishReason =
                builder.finishReason != null ? builder.finishReason : FinishReason.UNKNOWN;
        this.toolCalls = builder.toolCalls == null ? java.util.List.of() : java.util.List.copyOf(builder.toolCalls);
        this.providerData = builder.providerData == null ? Map.of() : Map.copyOf(builder.providerData);
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

    /** What the provider needs sent back unchanged with the tool results; copy it onto the assistant {@link Message}. */
    public Map<String, Object> getProviderData() {
        return providerData;
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
                && Objects.equals(providerData, that.providerData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(content, model, tokenUsage, finishReason, metadata, toolCalls, providerData);
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

        private Builder() {}

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
