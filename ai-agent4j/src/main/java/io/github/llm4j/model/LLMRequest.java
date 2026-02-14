package io.github.llm4j.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a request to an LLM.
 * This class is immutable and thread-safe.
 */
public final class LLMRequest {
    
    private final List<Message> messages;
    private final String model;
    private final Double temperature;
    private final Integer maxTokens;
    private final Double topP;
    private final List<String> stopSequences;
    private final Map<String, Object> additionalParameters;
    
    private LLMRequest(Builder builder) {
        this.messages = Collections.unmodifiableList(new ArrayList<>(builder.messages));
        this.model = builder.model;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.topP = builder.topP;
        this.stopSequences = builder.stopSequences != null ? 
            Collections.unmodifiableList(new ArrayList<>(builder.stopSequences)) : null;
        this.additionalParameters = builder.additionalParameters != null ?
            Collections.unmodifiableMap(new HashMap<>(builder.additionalParameters)) : Collections.emptyMap();
        
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
    
    public Map<String, Object> getAdditionalParameters() {
        return additionalParameters;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LLMRequest that = (LLMRequest) o;
        return Objects.equals(messages, that.messages) &&
               Objects.equals(model, that.model) &&
               Objects.equals(temperature, that.temperature) &&
               Objects.equals(maxTokens, that.maxTokens) &&
               Objects.equals(topP, that.topP) &&
               Objects.equals(stopSequences, that.stopSequences) &&
               Objects.equals(additionalParameters, that.additionalParameters);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(messages, model, temperature, maxTokens, topP, stopSequences, additionalParameters);
    }
    
    @Override
    public String toString() {
        return "LLMRequest{" +
                "messages=" + messages +
                ", model='" + model + '\'' +
                ", temperature=" + temperature +
                ", maxTokens=" + maxTokens +
                ", topP=" + topP +
                ", stopSequences=" + stopSequences +
                ", additionalParameters=" + additionalParameters +
                '}';
    }
    
    public static final class Builder {
        private List<Message> messages = new ArrayList<>();
        private String model;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;
        private List<String> stopSequences;
        private Map<String, Object> additionalParameters;
        
        private Builder() {
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
