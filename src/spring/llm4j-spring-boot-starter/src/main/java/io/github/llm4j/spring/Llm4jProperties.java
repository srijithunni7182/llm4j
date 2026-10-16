package io.github.llm4j.spring;

import io.github.llm4j.privacy.PIIType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * The {@code llm4j.*} settings. Each maps to a plain llm4j setting; nothing is read unless it is set, and a bean is created only for what you configured.
 *
 * @param enabled set to false to switch the whole starter off
 * @param primary which client is the {@code LLMClient} bean
 * @param provider shorthand for {@code llm4j.clients.default.provider}
 * @param model shorthand for {@code llm4j.clients.default.model}
 * @param apiKey shorthand for {@code llm4j.clients.default.api-key}
 * @param baseUrl shorthand for {@code llm4j.clients.default.base-url}
 * @param timeout shorthand for {@code llm4j.clients.default.timeout}
 * @param connectTimeout shorthand for {@code llm4j.clients.default.connect-timeout}
 * @param clients the provider clients by name
 */
@ConfigurationProperties(prefix = "llm4j")
public record Llm4jProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("default") String primary,
        String provider, String model, String apiKey, String baseUrl, Duration timeout, Duration connectTimeout,
        @DefaultValue Secrets secrets,
        Map<String, Client> clients,
        @DefaultValue Routing routing,
        @DefaultValue Pricing pricing,
        @DefaultValue Budget budget,
        @DefaultValue Privacy privacy,
        @DefaultValue Audit audit,
        @DefaultValue Agent agent,
        @DefaultValue Memory memory,
        @DefaultValue Prompts prompts,
        @DefaultValue Skills skills,
        @DefaultValue Tasks tasks,
        @DefaultValue Tools tools,
        @DefaultValue Rag rag,
        @DefaultValue Loom loom,
        @DefaultValue Observe observe) {

    /** Where API keys and passwords come from: {@code env}, {@code encrypted-file} or {@code chained}. */
    public record Secrets(String store, String file, String masterKeyEnv, String masterKeyFile, List<String> chain) { }

    /** One provider client. Give the key as {@code api-key-secret} (a name in the secret store); {@code api-key} is a literal. */
    public record Client(String provider, String model, String apiKey, String apiKeySecret, String baseUrl, Duration timeout, Duration connectTimeout,
                         String tier, String effort, @DefaultValue Retry retry) {
        @Override
        public String toString() {
            return "Client[provider=" + provider + ", model=" + model + ", apiKey=" + (apiKey == null ? "null" : "***") + ", apiKeySecret=" + apiKeySecret + ", baseUrl=" + baseUrl + "]";
        }
    }

    /** Retries and rate-limit waits; unset fields keep the library's defaults. */
    public record Retry(Boolean enabled, Integer maxRetries, Duration initialBackoff, Duration maxBackoff, Set<Integer> retryableStatusCodes,
                        Duration inlineWaitThreshold, Duration fallbackDelay, Duration maxFallbackDelay, ZoneId dailyResetZone) { }

    /** {@code none}, {@code fallback} or {@code cost-aware}, over the named clients. */
    public record Routing(@DefaultValue("none") String strategy, List<String> clients) { }

    public record Pricing(String file, Map<String, Price> models) {
        public record Price(BigDecimal inputPerMillion, BigDecimal outputPerMillion) { }
    }

    /** A hard cap on every model call; {@code window} is {@code minute}, {@code hour} or {@code day}. */
    public record Budget(Long tokens, Long calls, String cost, Double warnAt, String window, Integer perCallMaxTokens) {
        boolean set() {
            return tokens != null || calls != null || cost != null;
        }
    }

    public record Privacy(@DefaultValue("false") boolean maskPii, List<PIIType> types) { }

    public record Audit(String file, DataSize maxFileSize, Integer maxFiles) { }

    /** Defaults for every agent built from the {@code ReActAgent.Builder} bean. */
    public record Agent(String client, String systemPrompt, String systemPromptId, String instructions, Integer maxIterations, Double temperature,
                        String toolCalling, Boolean streaming, Boolean reasoning, Boolean validateToolArguments, Integer maxTokensPerCall,
                        String onBudgetExhausted, List<String> tools, List<String> skills, @DefaultValue Context context) {
        public record Context(Integer window, String windowsFile, String compaction, Map<String, String> compactionOptions, String compactionClient) { }
    }

    /** {@code memory} or {@code file} conversations. */
    public record Memory(String conversations, String dir, @DefaultValue("50") int maxHistory) { }

    public record Prompts(String location) { }

    public record Skills(List<String> locations, @DefaultValue("false") boolean allowRemote) { }

    public record Tasks(@DefaultValue("true") boolean discover) { }

    /** Built-in tools by name, and guarded tools of the six kinds (files and outboxes they use must be inside {@code base-dir}, the working directory by default) ({@code webhook}, {@code email}, {@code http}, {@code file}, {@code shell}, {@code sql}). */
    public record Tools(List<String> builtin, Map<String, Custom> custom, String baseDir) {
        public record Custom(String kind, Map<String, String> options) { }
    }

    public record Rag(String embedding, String store, String file, String apiKey, String apiKeySecret, String baseUrl, String model, String tokenizer, @DefaultValue Pgvector pgvector,
                      @DefaultValue Pinecone pinecone) {
        public record Pgvector(String url, String user, String password, String passwordSecret, @DefaultValue("rag_documents") String table, Integer dimension) {
            @Override
            public String toString() {
                return "Pgvector[url=" + url + ", user=" + user + ", password=" + (password == null ? "null" : "***") + ", passwordSecret=" + passwordSecret + ", table=" + table + "]";
            }
        }

        public record Pinecone(String apiKey, String apiKeySecret, String index, String namespace) {
            @Override
            public String toString() {
                return "Pinecone[apiKey=" + (apiKey == null ? "null" : "***") + ", apiKeySecret=" + apiKeySecret + ", index=" + index + "]";
            }
        }

        @Override
        public String toString() {
            return "Rag[embedding=" + embedding + ", store=" + store + ", apiKey=" + (apiKey == null ? "null" : "***") + ", apiKeySecret=" + apiKeySecret + "]";
        }
    }

    public record Loom(String script, String loot, @DefaultValue("strict") String validate, @DefaultValue("none") String persistence, String dir, String datasource,
                       @DefaultValue("embedded") String initializeSchema, @DefaultValue("false") boolean allowNonDurable,
                       @DefaultValue("platform") String executor, @DefaultValue("32") int maxConcurrentRuns, @DefaultValue("30s") Duration shutdownGrace,
                       @DefaultValue LoomBudget budget, @DefaultValue LoomContext context, @DefaultValue LoomAudit audit, @DefaultValue Triggers triggers) {
        public record LoomBudget(Long tokens, Long calls, String cost) { }
        public record LoomContext(List<String> windowsFiles) { }
        public record LoomAudit(@DefaultValue("high") String failOn) { }
        public record Triggers(@DefaultValue("false") boolean enabled, @DefaultValue("15s") Duration pollInterval, @DefaultValue("5m") Duration staleAfter, String owner) { }
    }

    public record Observe(@DefaultValue("false") boolean enabled, @DefaultValue("8787") int port, @DefaultValue("127.0.0.1") String bind,
                          @DefaultValue("metadata") String detail, String name, Duration linger, String rulesFile, @DefaultValue Fabric fabric) {
        public record Fabric(String url, String ca, String outbox) { }
    }

    /** Never prints a key: the shorthand {@code api-key} and every client's are masked. */
    @Override
    public String toString() {
        return "Llm4jProperties[enabled=" + enabled + ", primary=" + primary + ", clients=" + namedClientsForDisplay() + "]";
    }

    private Map<String, Client> namedClientsForDisplay() {
        return clients == null ? Map.of() : clients;
    }

    /**
     * The clients by name, with the v1 shorthands ({@code llm4j.provider} and friends) as the client {@code default}.
     *
     * @throws Llm4jConfigurationException if a shorthand and its long form are both set
     */
    public Map<String, Client> namedClients() {
        Map<String, Client> all = new LinkedHashMap<>(clients == null ? Map.of() : clients);
        boolean shorthand = provider != null || model != null || apiKey != null || baseUrl != null || timeout != null || connectTimeout != null;
        if (!shorthand) return all;
        if (all.containsKey("default")) {
            throw new Llm4jConfigurationException("llm4j.provider (and model, api-key, base-url, timeout, connect-timeout) and llm4j.clients.default are both set",
                    "Keep one: the long form llm4j.clients.default.* is the same client.");
        }
        Map<String, Client> withDefault = new LinkedHashMap<>();
        withDefault.put("default", new Client(provider, model, apiKey, null, baseUrl, timeout, connectTimeout, null, null, new Retry(null, null, null, null, null, null, null, null, null)));
        withDefault.putAll(all);
        return withDefault;
    }
}
