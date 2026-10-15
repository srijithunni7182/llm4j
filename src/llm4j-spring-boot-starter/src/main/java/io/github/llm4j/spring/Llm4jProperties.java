package io.github.llm4j.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code llm4j.*} settings. Every one maps to a plain llm4j setting; nothing here is read unless it is set, and a bean
 * is only created for what you configured.
 */
@ConfigurationProperties(prefix = "llm4j")
public class Llm4jProperties {

    /** Set to false to switch the whole starter off. */
    private boolean enabled = true;

    /** The provider the {@code LLMClient} bean talks to: gemini, anthropic, ollama or sarvam. No client is created while this is unset. */
    private String provider;

    /** The model the client uses when a request names none. */
    private String model;

    /** The provider's API key. Not needed for ollama. Prefer an environment variable or a secret store over a literal in a file. */
    private String apiKey;

    /** The provider's address, when it is not the provider's own (a proxy, or ollama's {@code http://host:11434}). */
    private String baseUrl;

    /** How long a request may take. */
    private Duration timeout;

    /** How long connecting may take. */
    private Duration connectTimeout;

    private final Loom loom = new Loom();

    private final Agent agent = new Agent();

    public static class Agent {

        /** Instructions every agent built from the {@code ReActAgent.Builder} bean starts with. Left to the agent's own default when unset. */
        private String systemPrompt;

        /** How many reason-and-act steps an agent may take for one question. Left to the agent's own default when unset. */
        private Integer maxIterations;

        /** The sampling temperature. Left to the provider's own default when unset. */
        private Double temperature;

        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
        public Integer getMaxIterations() { return maxIterations; }
        public void setMaxIterations(Integer maxIterations) { this.maxIterations = maxIterations; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
    }

    public static class Loom {

        /** The {@code .loom} script to load as the {@code LoomScript} bean ({@code classpath:} or {@code file:}). No Loom beans are created while this is unset and the application has no {@code LoomScript} of its own. */
        private String script;

        /** A {@code .loot} file mapping tool names to classes, added to the Loom tool registry ({@code classpath:} or {@code file:}). */
        private String loot;

        public String getScript() { return script; }
        public void setScript(String script) { this.script = script; }
        public String getLoot() { return loot; }
        public void setLoot(String loot) { this.loot = loot; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Loom getLoom() { return loom; }
    public Agent getAgent() { return agent; }
}
