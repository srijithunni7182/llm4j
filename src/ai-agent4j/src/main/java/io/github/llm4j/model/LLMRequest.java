package io.github.llm4j.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Represents a request to an LLM. This class is immutable and thread-safe. */
public final class LLMRequest {

    private final List<Message> messages;
    private final String model;
    private final Double temperature;
    private final Integer maxTokens;
    private final Double topP;
    private final List<String> stopSequences;
    private final ComplexityHint complexityHint;
    private final Map<String, Object> additionalParameters;
    private final List<ToolSpec> tools;
    private final ToolChoice toolChoice;
    private final boolean reasoning;
    private final io.github.llm4j.grounding.Grounding grounding;

    /** Whether the model may call the tools it is offered. */
    public enum ToolChoice {
        /** The model decides (the default). */
        AUTO,
        /** The model must answer in text. */
        NONE
    }

    private LLMRequest(Builder builder) {
        this.messages = Collections.unmodifiableList(new ArrayList<>(builder.messages));
        this.model = builder.model;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.topP = builder.topP;
        this.stopSequences =
                builder.stopSequences != null
                        ? Collections.unmodifiableList(new ArrayList<>(builder.stopSequences))
                        : null;
        this.complexityHint = builder.complexityHint;
        this.additionalParameters =
                builder.additionalParameters != null
                        ? Collections.unmodifiableMap(new HashMap<>(builder.additionalParameters))
                        : Collections.emptyMap();

        this.tools = builder.tools == null ? List.of() : List.copyOf(builder.tools);
        this.toolChoice = builder.toolChoice == null ? ToolChoice.AUTO : builder.toolChoice;
        this.reasoning = builder.reasoning;
        this.grounding = builder.grounding;

        validate();
    }

    private void validate() {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages cannot be null or empty");
        }
        if (temperature != null && (temperature < 0.0 || temperature > 2.0)) {
            throw new IllegalArgumentException("temperature must be between 0.0 and 2.0");
        }
        if (maxTokens != null && maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (topP != null && (topP < 0.0 || topP > 1.0)) {
            throw new IllegalArgumentException("topP must be between 0.0 and 1.0");
        }
    }

    public List<Message> getMessages() {
        return messages;
    }

    public String getModel() {
        return model;
    }

    public Double getTemperature() {
        return temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public Double getTopP() {
        return topP;
    }

    public List<String> getStopSequences() {
        return stopSequences;
    }

    public ComplexityHint getComplexityHint() {
        return complexityHint;
    }

    public Map<String, Object> getAdditionalParameters() {
        return additionalParameters;
    }

    /** The tools offered to the model (empty when none). */
    public List<ToolSpec> getTools() {
        return tools;
    }

    public ToolChoice getToolChoice() {
        return toolChoice;
    }

    /**
     * Whether a streamed answer should also carry the model's reasoning; see {@link
     * Builder#reasoning(boolean)}.
     */
    public boolean isReasoning() {
        return reasoning;
    }

    /**
     * The grounding asked for (provider-side web search with citations), or null when the request
     * is not grounded, which is the default: a request without it is sent exactly as it was before
     * grounding existed.
     */
    public io.github.llm4j.grounding.Grounding getGrounding() {
        return grounding;
    }

    /** A builder holding everything of this request, so a copy never drops a field. */
    public Builder toBuilder() {
        Builder b =
                builder()
                        .messages(messages)
                        .model(model)
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .topP(topP)
                        .stopSequences(stopSequences)
                        .complexityHint(complexityHint)
                        .tools(tools)
                        .toolChoice(toolChoice)
                        .reasoning(reasoning)
                        .grounding(grounding);
        if (!additionalParameters.isEmpty()) b.additionalParameters(additionalParameters);
        return b;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LLMRequest that = (LLMRequest) o;
        return Objects.equals(messages, that.messages)
                && Objects.equals(model, that.model)
                && Objects.equals(temperature, that.temperature)
                && Objects.equals(maxTokens, that.maxTokens)
                && Objects.equals(topP, that.topP)
                && Objects.equals(stopSequences, that.stopSequences)
                && Objects.equals(complexityHint, that.complexityHint)
                && Objects.equals(additionalParameters, that.additionalParameters)
                && Objects.equals(tools, that.tools)
                && toolChoice == that.toolChoice
                && reasoning == that.reasoning
                && Objects.equals(grounding, that.grounding);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                messages,
                model,
                temperature,
                maxTokens,
                topP,
                stopSequences,
                complexityHint,
                additionalParameters,
                tools,
                toolChoice,
                reasoning,
                grounding);
    }

    @Override
    public String toString() {
        return "LLMRequest{"
                + "messages="
                + messages
                + ", model='"
                + model
                + '\''
                + ", temperature="
                + temperature
                + ", maxTokens="
                + maxTokens
                + ", topP="
                + topP
                + ", stopSequences="
                + stopSequences
                + ", complexityHint="
                + complexityHint
                + ", additionalParameters="
                + additionalParameters
                + '}';
    }

    public static final class Builder {
        private List<Message> messages = new ArrayList<>();
        private String model;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;
        private List<String> stopSequences;
        private ComplexityHint complexityHint;
        private Map<String, Object> additionalParameters;
        private List<ToolSpec> tools;
        private ToolChoice toolChoice;
        private boolean reasoning;
        private io.github.llm4j.grounding.Grounding grounding;

        private Builder() {}

        /**
         * Asks the model to show its reasoning on a streamed call ({@code chatStream}); it arrives
         * as chunks whose {@link LLMResponse#getReasoning()} is set. Off by default, because it
         * changes the request: the provider is told to think and to report what it thought, which
         * can cost more and, on some models, takes the place of settings such as temperature. A
         * provider or model that cannot show reasoning ignores this. Has no effect on {@code chat}.
         */
        /**
         * Asks the provider to search the web for this request and return the sources it used (see
         * {@link io.github.llm4j.grounding.Grounding}). Null (the default) means no grounding. A
         * provider that cannot ground, or cannot honour a setting, makes the client refuse the
         * request before any network call.
         */
        public Builder grounding(io.github.llm4j.grounding.Grounding grounding) {
            this.grounding = grounding;
            return this;
        }

        /** As {@link #grounding(io.github.llm4j.grounding.Grounding)}, taking the builder. */
        public Builder grounding(io.github.llm4j.grounding.Grounding.Builder grounding) {
            this.grounding = grounding == null ? null : grounding.build();
            return this;
        }

        public Builder reasoning(boolean reasoning) {
            this.reasoning = reasoning;
            return this;
        }

        public Builder tools(List<ToolSpec> tools) {
            this.tools = tools;
            return this;
        }

        public Builder toolChoice(ToolChoice toolChoice) {
            this.toolChoice = toolChoice;
            return this;
        }

        public Builder messages(List<Message> messages) {
            this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
            return this;
        }

        public Builder addMessage(Message message) {
            this.messages.add(message);
            return this;
        }

        public Builder addSystemMessage(String content) {
            return addMessage(Message.system(content));
        }

        public Builder addUserMessage(String content) {
            return addMessage(Message.user(content));
        }

        public Builder addAssistantMessage(String content) {
            return addMessage(Message.assistant(content));
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        public Builder stopSequences(List<String> stopSequences) {
            this.stopSequences = stopSequences;
            return this;
        }

        public Builder complexityHint(ComplexityHint complexityHint) {
            this.complexityHint = complexityHint;
            return this;
        }

        public Builder additionalParameters(Map<String, Object> additionalParameters) {
            this.additionalParameters = additionalParameters;
            return this;
        }

        public Builder addParameter(String key, Object value) {
            if (this.additionalParameters == null) {
                this.additionalParameters = new HashMap<>();
            }
            this.additionalParameters.put(key, value);
            return this;
        }

        public LLMRequest build() {
            return new LLMRequest(this);
        }
    }
}
