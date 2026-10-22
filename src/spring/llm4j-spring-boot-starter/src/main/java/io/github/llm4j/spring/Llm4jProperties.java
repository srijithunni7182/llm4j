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
 * The {@code llm4j.*} settings. Each maps to a plain llm4j setting; nothing is read unless it is
 * set, and a bean is created only for what you configured.
 *
 * @param enabled Switch the whole starter off.
 * @param primary Which named client is the {@code LLMClient} bean.
 * @param provider Shorthand for llm4j.clients.default.provider.
 * @param model Shorthand for llm4j.clients.default.model.
 * @param apiKey Shorthand for llm4j.clients.default.api-key (a literal; prefer api-key-secret on a
 *     named client).
 * @param baseUrl Shorthand for llm4j.clients.default.base-url.
 * @param timeout Shorthand for llm4j.clients.default.timeout.
 * @param connectTimeout Shorthand for llm4j.clients.default.connect-timeout.
 * @param secrets Where API keys and passwords come from.
 * @param clients The provider clients, by name.
 * @param classifiers The decision-model backends for typed questions (Jev, Perplexity Decisions),
 *     by name.
 * @param routing How the named clients are combined into one.
 * @param pricing Prices per model, for cost budgets and cost-aware routing.
 * @param budget A hard cap on every model call.
 * @param privacy Masking of personal data before it leaves the application.
 * @param audit The audit log of model calls.
 * @param agent Defaults for every agent built from the ReActAgent.Builder bean.
 * @param memory Conversation storage.
 * @param prompts Where named prompts are read from.
 * @param skills Skill files an agent can load.
 * @param tasks Discovery of tasks for workflows.
 * @param tools Built-in and guarded tools.
 * @param rag Embeddings and the vector store.
 * @param loom Running Loom workflows from the application.
 * @param observe The run page.
 */
@ConfigurationProperties(prefix = "llm4j")
public record Llm4jProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("default") String primary,
        String provider,
        String model,
        String apiKey,
        String baseUrl,
        Duration timeout,
        Duration connectTimeout,
        @DefaultValue Secrets secrets,
        Map<String, Client> clients,
        Map<String, Decider> classifiers,
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

    /**
     * Where API keys and passwords come from.
     *
     * @param store The secret store: env, encrypted-file or chained.
     * @param file The encrypted secrets file (encrypted-file store).
     * @param masterKeyEnv Name of the environment variable holding the master key.
     * @param masterKeyFile File holding the master key.
     * @param chain Stores to try in order (chained store): env, encrypted-file.
     */
    public record Secrets(
            String store,
            String file,
            String masterKeyEnv,
            String masterKeyFile,
            List<String> chain) {}

    /**
     * One provider client. Give the key as {@code api-key-secret} (a name in the secret store);
     * {@code api-key} is a literal.
     *
     * @param provider gemini, anthropic or ollama.
     * @param model Default model for this client.
     * @param apiKey The key itself (a literal; avoid in files that are committed).
     * @param apiKeySecret Name of the key in the secret store.
     * @param baseUrl Override the provider address.
     * @param timeout Request timeout.
     * @param connectTimeout Connection timeout.
     * @param tier Service tier, where the provider has one.
     * @param effort Reasoning effort (anthropic).
     * @param retry Retries and rate-limit waits.
     */
    public record Client(
            String provider,
            String model,
            String apiKey,
            String apiKeySecret,
            String baseUrl,
            Duration timeout,
            Duration connectTimeout,
            String tier,
            String effort,
            @DefaultValue Retry retry) {
        @Override
        public String toString() {
            return "Client[provider="
                    + provider
                    + ", model="
                    + model
                    + ", apiKey="
                    + (apiKey == null ? "null" : "***")
                    + ", apiKeySecret="
                    + apiKeySecret
                    + ", baseUrl="
                    + baseUrl
                    + "]";
        }
    }

    /**
     * One decision-model backend: it answers typed questions (a choice, a yes/no, a score) with
     * probabilities and does not chat. Give the key as {@code api-key-secret}.
     *
     * @param provider jev or perplexity_decisions.
     * @param model The model (jev-latest, pplx-decider-latest or pplx-decider-flash-latest); unset
     *     uses the provider's default.
     * @param apiKey The key itself (a literal; avoid in files that are committed).
     * @param apiKeySecret Name of the key in the secret store.
     * @param baseUrl Override the provider address.
     * @param timeout Request timeout.
     * @param connectTimeout Connection timeout.
     * @param retry Retries and rate-limit waits.
     */
    public record Decider(
            String provider,
            String model,
            String apiKey,
            String apiKeySecret,
            String baseUrl,
            Duration timeout,
            Duration connectTimeout,
            @DefaultValue Retry retry) {
        @Override
        public String toString() {
            return "Decider[provider="
                    + provider
                    + ", model="
                    + model
                    + ", apiKey="
                    + (apiKey == null ? "null" : "***")
                    + ", apiKeySecret="
                    + apiKeySecret
                    + ", baseUrl="
                    + baseUrl
                    + "]";
        }
    }

    /**
     * Retries and rate-limit waits; unset fields keep the library defaults.
     *
     * @param enabled Retry failed calls.
     * @param maxRetries How many retries.
     * @param initialBackoff First wait between retries.
     * @param maxBackoff Longest wait between retries.
     * @param retryableStatusCodes HTTP statuses worth retrying.
     * @param inlineWaitThreshold Rate-limit waits up to this long are done in place.
     * @param fallbackDelay Wait when the provider gives no hint.
     * @param maxFallbackDelay Longest such wait.
     * @param dailyResetZone Time zone in which daily quotas reset.
     */
    public record Retry(
            Boolean enabled,
            Integer maxRetries,
            Duration initialBackoff,
            Duration maxBackoff,
            Set<Integer> retryableStatusCodes,
            Duration inlineWaitThreshold,
            Duration fallbackDelay,
            Duration maxFallbackDelay,
            ZoneId dailyResetZone) {}

    /**
     * How the named clients are combined into one.
     *
     * @param strategy none, fallback or cost-aware.
     * @param clients The clients to route over, in order.
     */
    public record Routing(@DefaultValue("none") String strategy, List<String> clients) {}

    /**
     * Prices per model.
     *
     * @param file A prices file (file: or classpath:).
     * @param models Prices by model name.
     */
    public record Pricing(String file, Map<String, Price> models) {
        /**
         * A model price.
         *
         * @param inputPerMillion Price per million input tokens.
         * @param outputPerMillion Price per million output tokens.
         * @param searchPerThousand Price per thousand web searches (grounded calls); unset means
         *     free or reported by the provider.
         */
        public record Price(
                BigDecimal inputPerMillion,
                BigDecimal outputPerMillion,
                BigDecimal searchPerThousand) {}
    }

    /**
     * A hard cap on every model call; {@code window} is minute, hour or day.
     *
     * @param tokens Token cap.
     * @param calls Call cap.
     * @param searches Web-search cap (grounded calls).
     * @param cost Cost cap, for example 5.00 (needs prices).
     * @param warnAt Fraction of the cap at which a warning is logged.
     * @param window minute, hour or day; unset means a total for the life of the application.
     * @param perCallMaxTokens Largest single call allowed (needed with a token cap).
     */
    public record Budget(
            Long tokens,
            Long calls,
            Long searches,
            String cost,
            Double warnAt,
            String window,
            Integer perCallMaxTokens) {
        boolean set() {
            return tokens != null || calls != null || searches != null || cost != null;
        }
    }

    /**
     * Masking of personal data.
     *
     * @param maskPii Mask personal data in prompts.
     * @param types Which kinds of data to mask; all when unset.
     */
    public record Privacy(@DefaultValue("false") boolean maskPii, List<PIIType> types) {}

    /**
     * The audit log of model calls.
     *
     * @param file Where to write it.
     * @param maxFileSize Rotate at this size.
     * @param maxFiles Rotated files to keep.
     */
    public record Audit(String file, DataSize maxFileSize, Integer maxFiles) {}

    /**
     * Defaults for every agent built from the {@code ReActAgent.Builder} bean.
     *
     * @param client Which named client; the primary when unset.
     * @param systemPrompt Literal system prompt.
     * @param systemPromptId Id of a prompt in the prompt registry.
     * @param instructions Extra instructions appended to the prompt.
     * @param maxIterations Reasoning steps allowed.
     * @param temperature Sampling temperature.
     * @param toolCalling auto or native.
     * @param streaming Stream tokens.
     * @param reasoning Ask for visible reasoning.
     * @param validateToolArguments Check tool arguments against their schema.
     * @param maxTokensPerCall Cap on a single call.
     * @param onBudgetExhausted return-partial or fail.
     * @param tools Names of tools from the tool catalog.
     * @param skills Names of loaded skills.
     * @param context Context window and compaction.
     */
    public record Agent(
            String client,
            String systemPrompt,
            String systemPromptId,
            String instructions,
            Integer maxIterations,
            Double temperature,
            String toolCalling,
            Boolean streaming,
            Boolean reasoning,
            Boolean validateToolArguments,
            Integer maxTokensPerCall,
            String onBudgetExhausted,
            List<String> tools,
            List<String> skills,
            @DefaultValue Context context) {
        /**
         * Context window and compaction.
         *
         * @param window Tokens the model accepts.
         * @param windowsFile File of model = tokens lines.
         * @param compaction Strategy name (any strategy the library or a jar provides).
         * @param compactionOptions Options for the strategy.
         * @param compactionClient Named client used to summarize.
         */
        public record Context(
                Integer window,
                String windowsFile,
                String compaction,
                Map<String, String> compactionOptions,
                String compactionClient) {}
    }

    /**
     * Conversation storage.
     *
     * @param conversations memory or file.
     * @param dir Directory for file storage.
     * @param maxHistory Messages kept per conversation.
     */
    public record Memory(String conversations, String dir, @DefaultValue("50") int maxHistory) {}

    /**
     * Named prompts.
     *
     * @param location Folder (file: or classpath:) of prompt files.
     */
    public record Prompts(String location) {}

    /**
     * Skill files.
     *
     * @param locations Skill files or folders (file: or classpath:).
     * @param allowRemote Allow skills fetched over the network.
     */
    public record Skills(List<String> locations, @DefaultValue("false") boolean allowRemote) {}

    /**
     * Tasks for workflows.
     *
     * @param discover Register every Task bean and Loom task found.
     */
    public record Tasks(@DefaultValue("true") boolean discover) {}

    /**
     * Built-in tools by name, and guarded tools of the six kinds: webhook, email, http, file,
     * shell, sql. Files and outboxes they use must be inside {@code base-dir}, the working
     * directory by default.
     *
     * @param builtin Built-in tools to register: calculator, current-time, date-time, echo,
     *     duckduckgo.
     * @param custom Guarded tools by name.
     * @param baseDir The directory guarded tools may touch.
     */
    public record Tools(List<String> builtin, Map<String, Custom> custom, String baseDir) {
        /**
         * A guarded tool.
         *
         * @param kind webhook, email, http, file, shell or sql.
         * @param options The kind's own options; secrets are given as option-secret names.
         */
        public record Custom(String kind, Map<String, String> options) {}
    }

    /**
     * Embeddings and the vector store.
     *
     * @param embedding gemini, onnx or djl.
     * @param store memory, file, pgvector or pinecone.
     * @param file File for the file store.
     * @param apiKey Embedding key (a literal).
     * @param apiKeySecret Name of the embedding key in the secret store.
     * @param baseUrl Override the embedding address.
     * @param model Embedding model.
     * @param tokenizer Tokenizer for local models.
     * @param pgvector pgvector settings.
     * @param pinecone Pinecone settings.
     */
    public record Rag(
            String embedding,
            String store,
            String file,
            String apiKey,
            String apiKeySecret,
            String baseUrl,
            String model,
            String tokenizer,
            @DefaultValue Pgvector pgvector,
            @DefaultValue Pinecone pinecone) {
        /**
         * pgvector settings.
         *
         * @param url JDBC url; spring.datasource.* is used when unset.
         * @param user Database user.
         * @param password Database password (a literal).
         * @param passwordSecret Name of the password in the secret store.
         * @param table Table name.
         * @param dimension Embedding dimension.
         */
        public record Pgvector(
                String url,
                String user,
                String password,
                String passwordSecret,
                @DefaultValue("rag_documents") String table,
                Integer dimension) {
            @Override
            public String toString() {
                return "Pgvector[url="
                        + url
                        + ", user="
                        + user
                        + ", password="
                        + (password == null ? "null" : "***")
                        + ", passwordSecret="
                        + passwordSecret
                        + ", table="
                        + table
                        + "]";
            }
        }

        /**
         * Pinecone settings.
         *
         * @param apiKey Key (a literal).
         * @param apiKeySecret Name of the key in the secret store.
         * @param index Index name.
         * @param namespace Namespace.
         */
        public record Pinecone(String apiKey, String apiKeySecret, String index, String namespace) {
            @Override
            public String toString() {
                return "Pinecone[apiKey="
                        + (apiKey == null ? "null" : "***")
                        + ", apiKeySecret="
                        + apiKeySecret
                        + ", index="
                        + index
                        + "]";
            }
        }

        @Override
        public String toString() {
            return "Rag[embedding="
                    + embedding
                    + ", store="
                    + store
                    + ", apiKey="
                    + (apiKey == null ? "null" : "***")
                    + ", apiKeySecret="
                    + apiKeySecret
                    + "]";
        }
    }

    /**
     * Running Loom workflows from the application.
     *
     * @param script The workflow file (classpath: or file:).
     * @param loot The tool-reach file (classpath: or file:).
     * @param validate strict or lenient.
     * @param persistence none, file or jdbc.
     * @param dir Directory for file persistence.
     * @param datasource Name of the DataSource bean for jdbc persistence.
     * @param initializeSchema always, embedded or never.
     * @param allowNonDurable Allow approvals without persistence.
     * @param executor platform, virtual-threads or application.
     * @param maxConcurrentRuns Runs allowed at once.
     * @param shutdownGrace How long running runs are given to finish on shutdown.
     * @param budget A budget applied to each run.
     * @param context Context windows for workflows.
     * @param audit The security audit gate.
     * @param triggers Schedule and wake-up triggers.
     */
    public record Loom(
            String script,
            String loot,
            @DefaultValue("strict") String validate,
            @DefaultValue("none") String persistence,
            String dir,
            String datasource,
            @DefaultValue("embedded") String initializeSchema,
            @DefaultValue("false") boolean allowNonDurable,
            @DefaultValue("platform") String executor,
            @DefaultValue("32") int maxConcurrentRuns,
            @DefaultValue("30s") Duration shutdownGrace,
            @DefaultValue LoomBudget budget,
            @DefaultValue LoomContext context,
            @DefaultValue LoomAudit audit,
            @DefaultValue Triggers triggers) {
        /**
         * A budget applied to each run.
         *
         * @param tokens Token cap.
         * @param calls Call cap.
         * @param cost Cost cap.
         */
        public record LoomBudget(Long tokens, Long calls, String cost) {}

        /**
         * Context windows for workflows.
         *
         * @param windowsFiles Files of model = tokens lines.
         */
        public record LoomContext(List<String> windowsFiles) {}

        /**
         * The security audit gate.
         *
         * @param failOn high, medium, low or info: refuse to start at this severity or worse.
         */
        public record LoomAudit(@DefaultValue("high") String failOn) {}

        /**
         * Schedule and wake-up triggers.
         *
         * @param enabled Start the trigger runner.
         * @param pollInterval How often triggers are checked.
         * @param staleAfter A claimed trigger is taken back after this long.
         * @param owner Name this application claims triggers under.
         */
        public record Triggers(
                @DefaultValue("false") boolean enabled,
                @DefaultValue("15s") Duration pollInterval,
                @DefaultValue("5m") Duration staleAfter,
                String owner) {}
    }

    /**
     * The run page.
     *
     * @param enabled Open the page.
     * @param port Port; 0 picks a free one.
     * @param bind Address to listen on.
     * @param detail full, truncated or metadata.
     * @param name Name shown on the page.
     * @param linger Keep the page open this long after the last run.
     * @param rulesFile Alert rules file.
     * @param fabric Send runs to a fabric.
     */
    public record Observe(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("8787") int port,
            @DefaultValue("127.0.0.1") String bind,
            @DefaultValue("metadata") String detail,
            String name,
            Duration linger,
            String rulesFile,
            @DefaultValue Fabric fabric) {
        /**
         * Send runs to a fabric.
         *
         * @param url Fabric address.
         * @param ca CA certificate file.
         * @param outbox Directory for undelivered events.
         */
        public record Fabric(String url, String ca, String outbox) {}
    }

    /** Never prints a key: the shorthand {@code api-key} and every client's are masked. */
    @Override
    public String toString() {
        return "Llm4jProperties[enabled="
                + enabled
                + ", primary="
                + primary
                + ", clients="
                + namedClientsForDisplay()
                + "]";
    }

    private Map<String, Client> namedClientsForDisplay() {
        return clients == null ? Map.of() : clients;
    }

    /**
     * The clients by name, with the v1 shorthands ({@code llm4j.provider} and friends) as the
     * client {@code default}.
     *
     * @throws Llm4jConfigurationException if a shorthand and its long form are both set
     */
    public Map<String, Client> namedClients() {
        Map<String, Client> all = new LinkedHashMap<>(clients == null ? Map.of() : clients);
        boolean shorthand =
                provider != null
                        || model != null
                        || apiKey != null
                        || baseUrl != null
                        || timeout != null
                        || connectTimeout != null;
        if (!shorthand) return all;
        if (all.containsKey("default")) {
            throw new Llm4jConfigurationException(
                    "llm4j.provider (and model, api-key, base-url, timeout, connect-timeout) and llm4j.clients.default are both set",
                    "Keep one: the long form llm4j.clients.default.* is the same client.");
        }
        Map<String, Client> withDefault = new LinkedHashMap<>();
        withDefault.put(
                "default",
                new Client(
                        provider,
                        model,
                        apiKey,
                        null,
                        baseUrl,
                        timeout,
                        connectTimeout,
                        null,
                        null,
                        new Retry(null, null, null, null, null, null, null, null, null)));
        withDefault.putAll(all);
        return withDefault;
    }
}
