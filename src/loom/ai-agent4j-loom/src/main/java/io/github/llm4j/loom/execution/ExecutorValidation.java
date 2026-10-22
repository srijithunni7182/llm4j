package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.KnowledgeDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RoutingPolicyDef;
import java.nio.file.Path;
import java.util.List;

/**
 * The load-time checks a {@link HarnessExecutor} contributes to {@link ScriptValidator}: model
 * providers, agent settings (memory, voice, guard, context), compaction strategies, knowledge
 * bases, and tools with their approvals. They need what the executor knows about its surroundings
 * (the secret store, the environment, the model factory), which they get through the narrow {@link
 * Host} interface and not by reaching into the executor.
 */
final class ExecutorValidation {

    /** What the checks need from the executor. */
    interface Host {
        LoomScript script();

        Path baseDir();

        /**
         * Resolves an {@code env.NAME} or {@code secret.NAME} reference; null when there is none.
         */
        String credential(String key);

        /** The value of an option that is a reference (null when unset or only known to a run). */
        String credentialValue(io.github.llm4j.loom.ast.ToolDef.OptionValue v);

        io.github.llm4j.secret.SecretStore secretStore();

        /** What is wrong with a model name, or null. */
        String modelProblem(String model);

        /** What is wrong with an embedding model name, or null. */
        String embeddingProblem(String model);

        io.github.llm4j.loom.ast.ProviderDef declaredProvider(String model);

        io.github.llm4j.secret.SecretRef storedKey(
                io.github.llm4j.loom.ast.ToolDef.OptionValue key);

        List<String> toolProblems(io.github.llm4j.loom.ast.ToolDef def);

        String agentToolProblem(
                io.github.llm4j.loom.ast.ToolDef def, String agent, boolean approved);

        /** Whether the host registered a tool of this name itself. */
        boolean hostRegistersTool(String name);

        java.util.Set<String> customCompactorNames();

        io.github.llm4j.loom.context.CompactorRegistry compactors();

        io.github.llm4j.loom.context.WindowLayers contextWindows();

        List<String> modelsOf(AgentDef agent);
    }

    private final Host host;

    ExecutorValidation(Host host) {
        this.host = host;
    }

    void checkProviders(ScriptValidator.Checker c) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (io.github.llm4j.loom.ast.ProviderDef p : host.script().getProviders()) {
            String who = "provider " + p.getName();
            if (!seen.add(p.getName())) c.error(p.getLine(), who, "declared twice");
            if (ProviderSpec.KINDS.contains(p.getName().toLowerCase(java.util.Locale.ROOT))) {
                c.error(
                        p.getLine(),
                        who,
                        "the name "
                                + p.getName()
                                + " is reserved for the built-in "
                                + p.getName().toLowerCase(java.util.Locale.ROOT)
                                + "/… models; pick another name");
            }
            if (!ProviderSpec.KINDS.contains(p.getKind())) {
                c.error(
                        p.getLine(),
                        who,
                        "unknown use: "
                                + p.getKind()
                                + "; use one of "
                                + String.join(", ", ProviderSpec.KINDS));
                continue;
            }
            for (var e : p.getOptions().entrySet()) {
                if (e.getValue().fromInvoker()) {
                    c.error(
                            p.getLine(),
                            who,
                            e.getKey()
                                    + " can't be "
                                    + e.getValue()
                                    + ": invoker credentials are for tools, not for the model provider");
                    continue;
                }
                switch (e.getKey()) {
                    case "base_url" -> {}
                    case "api", "header", "query" -> checkVendorOption(
                            c, p, who, e.getKey(), e.getValue());
                    case "api_key" -> {
                        if (!e.getValue().isReference()) {
                            c.error(
                                    p.getLine(),
                                    who,
                                    "api_key must not be written in the script: use api_key: secret.MY_KEY (or env.MY_KEY)");
                        } else {
                            String found = host.credentialValue(e.getValue());
                            if (found == null || found.isBlank()) {
                                c.error(
                                        p.getLine(),
                                        who,
                                        e.getValue().fromSecret()
                                                ? "secret "
                                                        + e.getValue().value()
                                                        + " is not in the secret store"
                                                        + (host.secretStore() == null
                                                                ? " (no secret store was given: pass one with --secrets, or HarnessExecutor.setSecretStore)"
                                                                : "")
                                                : "environment variable "
                                                        + e.getValue().value()
                                                        + " is not set (and there is no secret of that name)");
                            }
                        }
                    }
                    default -> c.error(
                            p.getLine(),
                            who,
                            "unknown option "
                                    + e.getKey()
                                    + "; use base_url, api_key"
                                    + (p.getKind().equals("openai_compatible")
                                            ? ", header, query"
                                            : p.getKind().equals("openai") ? ", api" : ""));
                }
            }
            if (p.getKind().equals("openai_compatible")
                    && !p.getOptions().containsKey("base_url")) {
                c.error(
                        p.getLine(),
                        who,
                        "openai_compatible needs base_url (the address of the server, e.g. https://api.groq.com/openai/v1)");
            }
            if (!p.getKind().equals("ollama")
                    && !p.getKind().equals("openai_compatible")
                    && !p.getOptions().containsKey("api_key")) {
                c.error(
                        p.getLine(),
                        who,
                        p.getKind() + " needs api_key: secret.<NAME> (or env.<NAME>)");
            }
            checkProviderEndpoint(c, p, who);
        }
        for (AgentDef a : host.script().getAgents()) {
            if (a.getRoutingPolicy() == null) {
                if (a.getModel() == null)
                    c.error(
                            a.getLine(),
                            "agent " + a.getName(),
                            "needs model: \"…\" or routing: <policy>");
                else checkModel(c, a.getLine(), "agent " + a.getName(), a.getModel());
            }
        }
        for (io.github.llm4j.loom.ast.WorkflowDef w : host.script().getWorkflows()) {
            io.github.llm4j.loom.ast.StatementWalker.walk(
                    w.getStatements(),
                    st -> {
                        if (st instanceof io.github.llm4j.loom.ast.RunStmt r
                                && r.getModel() != null) {
                            checkModel(c, r.getLine(), "run " + r.getTaskName(), r.getModel());
                        }
                    });
        }
        for (RoutingPolicyDef r : host.script().getRoutingPolicies()) {
            if (r.getPrimaryModel() != null)
                checkModel(c, r.getLine(), "routing " + r.getName(), r.getPrimaryModel());
            for (String fb : r.getFallbackModels())
                checkModel(c, r.getLine(), "routing " + r.getName(), fb);
        }
    }

    /**
     * {@code api} (OpenAI and xAI: chat or responses), {@code header} and {@code query} (the
     * generic kind only): the settings the OpenAI-family kinds accept beyond the address and the
     * key.
     */
    private void checkVendorOption(
            ScriptValidator.Checker c,
            io.github.llm4j.loom.ast.ProviderDef p,
            String who,
            String option,
            io.github.llm4j.loom.ast.ToolDef.OptionValue value) {
        boolean allowed =
                option.equals("api")
                        ? p.getKind().equals("openai") || p.getKind().equals("xai")
                        : p.getKind().equals("openai_compatible");
        if (!allowed) {
            c.error(
                    p.getLine(),
                    who,
                    "the option "
                            + option
                            + " is only for use: "
                            + (option.equals("api") ? "openai or xai" : "openai_compatible"));
            return;
        }
        if (value.isReference()) {
            c.error(
                    p.getLine(),
                    who,
                    option + " is written in the script, not read from env or secret");
            return;
        }
        String v = value.value();
        switch (option) {
            case "api" -> {
                if (!v.equals("chat") && !v.equals("responses"))
                    c.error(p.getLine(), who, "api must be chat or responses, got " + v);
            }
            case "header" -> {
                if (!v.matches("[A-Za-z0-9-]{1,64}"))
                    c.error(
                            p.getLine(),
                            who,
                            "header must be a header name (letters, digits, -), got " + v);
            }
            default -> {
                for (String pair : v.split("&")) {
                    if (!pair.isBlank() && pair.indexOf('=') <= 0) {
                        c.error(
                                p.getLine(),
                                who,
                                "query must be name=value pairs joined with &, got " + pair);
                        break;
                    }
                }
            }
        }
    }

    /**
     * A key is only ever sent to an endpoint it is safe for: https (or http on this machine), and,
     * for a key in the store, a host the secret allows. Otherwise a script could send a credential
     * in clear text, or to a host of its own choosing.
     */
    void checkProviderEndpoint(
            ScriptValidator.Checker c, io.github.llm4j.loom.ast.ProviderDef p, String who) {
        io.github.llm4j.loom.ast.ToolDef.OptionValue keyOption = p.getOptions().get("api_key");
        io.github.llm4j.loom.ast.ToolDef.OptionValue urlOption = p.getOptions().get("base_url");
        if (keyOption == null || urlOption == null) return;
        String url = urlOption.isReference() ? host.credentialValue(urlOption) : urlOption.value();
        if (url == null || url.isBlank()) return;
        String endpoint = io.github.llm4j.secret.SecretMetadata.hostOf(url);
        boolean https = url.regionMatches(true, 0, "https://", 0, 8);
        boolean local =
                endpoint != null
                        && (endpoint.equals("localhost")
                                || endpoint.equals("127.0.0.1")
                                || endpoint.equals("::1"));
        if (!https && !local) {
            c.error(
                    p.getLine(),
                    who,
                    "base_url "
                            + url
                            + " is not https, so api_key would travel unencrypted; use https:// (http is only allowed for localhost)");
        }
        io.github.llm4j.secret.SecretRef ref = host.storedKey(keyOption);
        if (ref != null
                && host.secretStore()
                        .metadata(keyOption.value())
                        .map(m -> !m.allows(endpoint))
                        .orElse(false)) {
            c.error(
                    p.getLine(),
                    who,
                    "secret "
                            + keyOption.value()
                            + " may not be sent to "
                            + endpoint
                            + " (its allowedHosts are "
                            + host.secretStore()
                                    .metadata(keyOption.value())
                                    .orElseThrow()
                                    .allowedHosts()
                            + ")");
        }
    }

    /**
     * True for a model served by Jev: the built-in {@code jev/…} or {@code jev-…}, or a provider
     * declared with {@code use: jev}.
     */
    boolean isJevModel(String model) {
        String m = model.toLowerCase(java.util.Locale.ROOT);
        if (m.startsWith("jev/") || m.startsWith("jev-")) return true;
        io.github.llm4j.loom.ast.ProviderDef declared = host.declaredProvider(model);
        return declared != null && "jev".equals(declared.getKind());
    }

    /**
     * The backend of each {@code intentclassifier}: a Jev model needs its key; any other must be a
     * model the client factory can serve.
     */
    void checkClassifiers(ScriptValidator.Checker c) {
        for (io.github.llm4j.loom.ast.IntentClassifierDef def :
                host.script().getIntentClassifiers()) {
            String model = def.getModel();
            if (model == null) continue;
            String who = "intentclassifier " + def.getName();
            int line = def.getModelLine();
            io.github.llm4j.loom.ast.ProviderDef declared = host.declaredProvider(model);
            if (declared != null) {
                if (model.substring(model.indexOf('/') + 1).isBlank())
                    c.error(line, who, "model " + model + " names no model after the provider");
            } else if (isJevModel(model)) {
                if (model.indexOf('/') >= 0 && model.substring(model.indexOf('/') + 1).isBlank()) {
                    c.error(line, who, "model " + model + " names no model after the provider");
                } else if (host.credential("JEV_API_KEY") == null
                        || host.credential("JEV_API_KEY").isBlank()) {
                    c.error(
                            line,
                            who,
                            "model "
                                    + model
                                    + " needs JEV_API_KEY in the environment or the secret store");
                }
            } else {
                String problem = host.modelProblem(model);
                if (problem != null) c.error(line, who, problem);
            }
        }
    }

    void checkModel(ScriptValidator.Checker c, int line, String who, String model) {
        if (isJevModel(model)) {
            c.error(
                    line,
                    who,
                    "model "
                            + model
                            + " is a Jev model: Jev answers typed questions, it does not chat. Use it in an intentclassifier, and give this a chat model");
            return;
        }
        if (host.declaredProvider(model) != null) {
            if (model.substring(model.indexOf('/') + 1).isBlank())
                c.error(line, who, "model " + model + " names no model after the provider");
            return;
        }
        String problem = host.modelProblem(model);
        if (problem != null) c.error(line, who, problem);
    }

    private static final java.util.Set<String> MEMORY_KEYS =
            java.util.Set.of(
                    "conversation",
                    "limit",
                    "session",
                    "facts",
                    "embedding",
                    "recall",
                    "min_similarity",
                    "max_tokens",
                    "compact");
    private static final java.util.Set<String> VOICE_KEYS =
            java.util.Set.of("listen", "speak", "language", "voice", "out");
    private static final java.util.Set<String> GUARD_KEYS =
            java.util.Set.of("pii", "bias", "bias_model");

    private static final java.util.Set<String> GROUNDING_KEYS =
            java.util.Set.of("allow", "block", "recency", "country", "max_searches");

    /** The script's name for each setting of a grounding, in the order they are listed. */
    private static final java.util.Map<String, io.github.llm4j.grounding.GroundingOption>
            GROUNDING_OPTIONS =
                    new java.util.LinkedHashMap<>(
                            java.util.Map.of(
                                    "max_searches",
                                    io.github.llm4j.grounding.GroundingOption.MAX_SEARCHES,
                                    "allow",
                                    io.github.llm4j.grounding.GroundingOption.ALLOW_DOMAINS));

    static {
        GROUNDING_OPTIONS.put("block", io.github.llm4j.grounding.GroundingOption.BLOCK_DOMAINS);
        GROUNDING_OPTIONS.put("recency", io.github.llm4j.grounding.GroundingOption.RECENCY);
        GROUNDING_OPTIONS.put("country", io.github.llm4j.grounding.GroundingOption.COUNTRY);
    }

    void checkAgentSettings(ScriptValidator.Checker c) {
        for (AgentDef a : host.script().getAgents()) {
            String who = "agent " + a.getName();
            if (a.getGrounding() != null) checkGrounding(c, who, a);
            if (a.getMemory() != null) checkMemory(c, who, a.getMemory());
            if (a.getVoice() != null) checkVoice(c, who, a.getVoice());
            if (a.getGuard() != null) checkGuard(c, who, a.getGuard());
            if (a.getContext() != null)
                ContextSettings.check(
                        c,
                        who,
                        a.getContext(),
                        host.customCompactorNames(),
                        (line, model) -> checkModel(c, line, who, model));
        }
    }

    /**
     * {@code grounding:} is refused where it cannot work, naming the model and the setting: a model
     * whose provider cannot search the web, or a setting that provider cannot honour (a script has
     * no {@code lenient}: its author sees every limit).
     */
    void checkGrounding(ScriptValidator.Checker c, String who, AgentDef a) {
        AgentDef.GroundingConfig g = a.getGrounding();
        checkKeys(c, who, "grounding", g, GROUNDING_KEYS);
        if (g.has("allow") && g.has("block"))
            c.error(g.getLine(), who, "grounding takes allow or block, not both");
        for (String key : new String[] {"allow", "block"}) {
            if (g.has(key) && (g.get(key) == null || g.get(key).isBlank()))
                c.error(g.lineOf(key), who, "grounding " + key + " names no domain");
        }
        String recency = g.getRecency();
        if (g.has("recency")
                && (recency == null
                        || !java.util.Set.of("day", "week", "month", "year").contains(recency)))
            c.error(
                    g.lineOf("recency"),
                    who,
                    "grounding recency must be day, week, month or year, got " + recency);
        String country = g.getCountry();
        if (g.has("country") && (country == null || !country.matches("[A-Za-z]{2}")))
            c.error(
                    g.lineOf("country"),
                    who,
                    "grounding country is a two-letter country code such as \"IN\", got "
                            + country);
        if (g.has("max_searches") && g.getMaxSearches() == null)
            c.error(
                    g.lineOf("max_searches"),
                    who,
                    "grounding max_searches must be a whole number of at least 1");
        for (String model : host.modelsOf(a)) {
            io.github.llm4j.loom.ast.ProviderDef declared = host.declaredProvider(model);
            String kind;
            boolean responses = false;
            String label;
            if (declared != null) {
                kind = declared.getKind();
                var api = declared.getOptions().get("api");
                responses = api != null && !api.isReference() && "responses".equals(api.value());
                label = "provider " + declared.getName() + " (use: " + kind + ")";
            } else {
                kind = ModelKinds.kindOf(model);
                label = kind == null ? "an unknown provider" : "the " + kind + " provider";
            }
            var options =
                    kind == null
                            ? java.util.Optional
                                    .<java.util.Set<io.github.llm4j.grounding.GroundingOption>>
                                            empty()
                            : io.github.llm4j.provider.GroundingCapabilities.options(
                                    kind, responses);
            if (options.isEmpty()) {
                if (kind != null) {
                    c.error(
                            g.getLine(),
                            who,
                            "grounding needs a model whose provider can search the web, and "
                                    + model
                                    + " ("
                                    + label
                                    + ") cannot; "
                                    + groundingHint(kind));
                }
                continue;
            }
            for (var e : GROUNDING_OPTIONS.entrySet()) {
                if (g.has(e.getKey()) && !options.get().contains(e.getValue())) {
                    c.error(
                            g.lineOf(e.getKey()),
                            who,
                            "grounding "
                                    + e.getKey()
                                    + " is not supported by "
                                    + model
                                    + " ("
                                    + label
                                    + "); it honours "
                                    + GROUNDING_OPTIONS.entrySet().stream()
                                            .filter(x -> options.get().contains(x.getValue()))
                                            .map(java.util.Map.Entry::getKey)
                                            .toList());
                }
            }
        }
    }

    private static String groundingHint(String kind) {
        return switch (kind) {
            case "openai", "xai" -> "declare a provider with use: "
                    + kind
                    + "  api: responses and name it in the model (\"Name/<model>\"), or use a perplexity/…, claude-… or gemini-… model";
            default -> "use a perplexity/<creator>/<model>, a claude-… or a gemini-… model, or an openai or xai provider with api: responses";
        };
    }

    /** Unknown keys and env references (these settings are written in the script, not secrets). */
    private static void checkKeys(
            ScriptValidator.Checker c,
            String who,
            String block,
            io.github.llm4j.loom.ast.Settings settings,
            java.util.Set<String> allowed) {
        for (var e : settings.getValues().entrySet()) {
            if (!allowed.contains(e.getKey())) {
                if (block.equals("memory")
                        && (e.getKey().equals("type") || e.getKey().equals("path"))) {
                    c.error(
                            settings.lineOf(e.getKey()),
                            who,
                            "memory "
                                    + e.getKey()
                                    + ": is no longer used; write "
                                    + "conversation: \"<directory>\" (or memory) for conversations, and facts: \"<file>\" with embedding: \"…\" for long-term facts");
                } else {
                    c.error(
                            settings.lineOf(e.getKey()),
                            who,
                            "unknown "
                                    + block
                                    + " setting "
                                    + e.getKey()
                                    + "; use "
                                    + new java.util.TreeSet<>(allowed));
                }
            } else if (e.getValue().isReference()) {
                c.error(
                        settings.lineOf(e.getKey()),
                        who,
                        block
                                + " "
                                + e.getKey()
                                + " is written in the script, not taken from the environment or a secret");
            }
        }
    }

    void checkMemory(ScriptValidator.Checker c, String who, AgentDef.MemoryConfig m) {
        checkKeys(c, who, "memory", m, MEMORY_KEYS);
        if (!m.has("conversation") && !m.has("facts") && !m.has("max_tokens")) {
            c.error(
                    m.getLine(),
                    who,
                    "memory needs conversation: \"<directory>\" | memory, or facts: \"<file>\" | memory (or both), or max_tokens: <n> to bound the shared transcript");
        }
        wholeNumber(c, who, m, "max_tokens");
        if (m.has("compact")) {
            if (!m.has("max_tokens"))
                c.warn(m.lineOf("compact"), who, "memory compact: only applies with max_tokens:");
            String name = m.get("compact");
            java.util.TreeSet<String> known =
                    ContextSettings.knownNames(host.customCompactorNames());
            known.remove("chain");
            if (name != null && !known.contains(name)) {
                c.error(
                        m.lineOf("compact"),
                        who,
                        "memory compact: unknown strategy '"
                                + name
                                + "'"
                                + io.github.llm4j.loom.autonomy.expr.Suggest.hint(name, known)
                                + "; use one of "
                                + String.join(", ", known));
            } else if ("summarize".equals(name)) {
                c.warn(
                        m.lineOf("compact"),
                        who,
                        "memory compact: summarize needs a model of its own, which the transcript does not have; it falls back to drop_oldest");
            }
        }
        wholeNumber(c, who, m, "limit");
        wholeNumber(c, who, m, "recall");
        if (m.has("min_similarity")) {
            double v = m.getDouble("min_similarity", -1);
            if (v < 0 || v > 1)
                c.error(
                        m.lineOf("min_similarity"),
                        who,
                        "memory min_similarity must be between 0 and 1");
        }
        String conversation = m.getConversation();
        if (conversation != null
                && !AgentMemory.MEMORY.equals(conversation)
                && java.nio.file.Files.isRegularFile(host.baseDir().resolve(conversation))) {
            c.error(
                    m.lineOf("conversation"),
                    who,
                    "memory conversation: " + conversation + " is a file; give a directory");
        }
        if (m.has("facts")) {
            if (m.getEmbedding() == null) {
                c.error(
                        m.lineOf("facts"),
                        who,
                        "memory facts need embedding: \"<model>\" (e.g. gemini/text-embedding-004)");
            } else {
                String problem = host.embeddingProblem(m.getEmbedding());
                if (problem != null) c.error(m.lineOf("embedding"), who, problem);
            }
            String facts = m.getFacts();
            if (facts != null
                    && !AgentMemory.MEMORY.equals(facts)
                    && java.nio.file.Files.isDirectory(host.baseDir().resolve(facts))) {
                c.error(
                        m.lineOf("facts"),
                        who,
                        "memory facts: "
                                + facts
                                + " is a directory; give a file, e.g. memory/facts.json");
            }
        } else if (m.has("embedding") || m.has("recall") || m.has("min_similarity")) {
            c.warn(
                    m.getLine(),
                    who,
                    "memory embedding/recall/min_similarity only apply with facts:");
        }
    }

    private static void wholeNumber(
            ScriptValidator.Checker c,
            String who,
            io.github.llm4j.loom.ast.Settings s,
            String key) {
        if (!s.has(key)) return;
        try {
            double v = Double.parseDouble(s.get(key));
            if (v < 1 || v != Math.floor(v)) throw new NumberFormatException();
        } catch (RuntimeException e) {
            c.error(
                    s.lineOf(key),
                    who,
                    key + " must be a positive whole number, got " + s.getValues().get(key));
        }
    }

    void checkVoice(ScriptValidator.Checker c, String who, AgentDef.VoiceConfig v) {
        checkKeys(c, who, "voice", v, VOICE_KEYS);
        if (!v.has("listen") && !v.has("speak"))
            c.error(
                    v.getLine(),
                    who,
                    "voice needs listen: \"sarvam/<model>\" or speak: \"sarvam/<model>\"");
        for (String key : List.of("listen", "speak")) {
            String model = v.get(key);
            if (model == null) continue;
            if (!model.startsWith(AgentVoice.PREFIX)
                    || model.length() == AgentVoice.PREFIX.length()) {
                c.error(
                        v.lineOf(key),
                        who,
                        "voice "
                                + key
                                + ": "
                                + model
                                + " is not supported; use sarvam/<model>, e.g. "
                                + (key.equals("speak")
                                        ? "sarvam/bulbul:v2"
                                        : "sarvam/saarika:v2.5"));
            }
        }
        if ((v.has("listen") || v.has("speak"))
                && (host.credential("SARVAM_API_KEY") == null
                        || host.credential("SARVAM_API_KEY").isBlank())) {
            c.error(v.getLine(), who, "voice needs SARVAM_API_KEY in the environment");
        }
        try {
            io.github.llm4j.tools.SafePaths.inside(host.baseDir(), v.getOut());
        } catch (IllegalArgumentException e) {
            c.error(v.lineOf("out"), who, "voice out: " + e.getMessage());
        }
    }

    /** The problems of the mapped strategies, as check messages. */
    void checkCompactors(ScriptValidator.Checker c) {
        java.util.List<io.github.llm4j.loom.context.CompactorRegistry.Problem> all =
                new java.util.ArrayList<>(host.compactors().loadProblems());
        all.addAll(host.compactors().verify());
        for (var p : all) {
            if (p.error()) c.error(p.line(), "context strategy", p.toString());
            else c.warn(p.line(), "context strategy", p.toString());
        }
        boolean used = host.script().getAgents().stream().anyMatch(a -> a.getContext() != null);
        if (!used) return;
        for (AgentDef a : host.script().getAgents()) {
            AgentDef.ContextConfig cfg = a.getContext();
            if (cfg == null || cfg.compactNames().equals(java.util.List.of("none"))) continue;
            java.util.List<String> models = host.modelsOf(a);
            if (ContextWiring.governing(host.contextWindows(), models).isEmpty()) {
                c.warn(
                        cfg.getLine(),
                        "agent " + a.getName(),
                        "no context window is known for "
                                + String.join(", ", models)
                                + ": compaction can only react to a provider's \"prompt too long\" error; add the model to a windows file (--context-windows, or a .windows file beside the script)");
            }
        }
    }

    void checkGuard(ScriptValidator.Checker c, String who, AgentDef.GuardConfig g) {
        checkKeys(c, who, "guard", g, GUARD_KEYS);
        if (!g.has("pii") && !g.has("bias"))
            c.error(
                    g.getLine(),
                    who,
                    "guard needs pii: mask | block | warn, or bias: warn | block");
        if (g.getPii() != null && !AgentGuard.PII_VALUES.contains(g.getPii())) {
            c.error(
                    g.lineOf("pii"),
                    who,
                    "guard pii: " + g.getPii() + " is not one of mask, block, warn");
        }
        if (g.getBias() != null && !AgentGuard.BIAS_VALUES.contains(g.getBias())) {
            c.error(
                    g.lineOf("bias"),
                    who,
                    "guard bias: " + g.getBias() + " is not one of warn, block");
        }
        if (g.has("bias_model")) {
            if (!g.has("bias"))
                c.error(g.lineOf("bias_model"), who, "guard bias_model needs bias: warn | block");
            else if (g.getBiasModel() != null)
                checkModel(c, g.lineOf("bias_model"), who, g.getBiasModel());
        }
    }

    void checkKnowledge(ScriptValidator.Checker c) {
        for (KnowledgeDef kb : host.script().getKnowledgeBases()) {
            String who = "knowledge " + kb.getName();
            if (kb.getType() != null) {
                c.warn(
                        kb.getLine(),
                        who,
                        "type: is no longer used (every knowledge base is searched by meaning); remove it");
            }
            if (kb.getSource() == null) {
                c.error(kb.getLine(), who, "needs source: \"<file or directory>\"");
            } else {
                Path src =
                        io.github.llm4j.loom.knowledge.KnowledgeIndexer.source(kb, host.baseDir());
                if (!java.nio.file.Files.exists(src)) {
                    c.error(
                            kb.getLine(),
                            who,
                            "source " + kb.getSource() + " does not exist (looked in " + src + ")");
                } else if (!hasIndexableFile(src)) {
                    c.warn(
                            kb.getLine(),
                            who,
                            "source "
                                    + kb.getSource()
                                    + " has no text files to index ("
                                    + String.join(
                                            ", ",
                                            new java.util.TreeSet<>(
                                                    io.github.llm4j.loom.knowledge.KnowledgeIndexer
                                                            .TEXT))
                                    + ")");
                }
            }
            String problem = host.embeddingProblem(kb.getEmbeddingProvider());
            if (problem != null) c.error(kb.getLine(), who, problem);
            if (kb.getChunkSize() < 1) c.error(kb.getLine(), who, "chunk_size must be positive");
            else if (kb.getOverlap() >= kb.getChunkSize())
                c.error(kb.getLine(), who, "overlap must be smaller than chunk_size");
        }
    }

    private static boolean hasIndexableFile(Path src) {
        try (java.util.stream.Stream<Path> all = java.nio.file.Files.walk(src)) {
            return all.filter(java.nio.file.Files::isRegularFile)
                    .anyMatch(
                            p -> {
                                String n = p.getFileName().toString();
                                int dot = n.lastIndexOf('.');
                                return dot > 0
                                        && io.github.llm4j.loom.knowledge.KnowledgeIndexer.TEXT
                                                .contains(n.substring(dot + 1).toLowerCase());
                            });
        } catch (java.io.IOException e) {
            return false;
        }
    }

    void checkToolsAndApprovals(ScriptValidator.Checker c) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (io.github.llm4j.loom.ast.ToolDef t : host.script().getTools()) {
            if (!seen.add(t.getName()))
                c.error(t.getLine(), "tool " + t.getName(), "declared twice");
            for (String problem : host.toolProblems(t))
                c.error(t.getLine(), "tool " + t.getName(), problem);
        }
        // Built-in names an agent uses (not declared, not registered by the host): their keys must
        // be set too
        java.util.Set<String> declared = new java.util.HashSet<>();
        host.script().getTools().forEach(t -> declared.add(t.getName()));
        java.util.Set<String> checkedBuiltIns = new java.util.HashSet<>();
        for (AgentDef a : host.script().getAgents()) {
            for (String name : a.getTools()) {
                if (declared.contains(name)
                        || host.hostRegistersTool(name)
                        || !checkedBuiltIns.add(name)) continue;
                io.github.llm4j.loom.ast.ToolDef builtIn =
                        io.github.llm4j.loom.tools.ToolFactory.builtIn(name);
                if (builtIn == null) continue;
                for (String problem : host.toolProblems(builtIn))
                    c.error(a.getLine(), "tool " + name, problem);
            }
        }
        for (AgentDef a : host.script().getAgents()) {
            String who = "agent " + a.getName();
            for (String name : a.getApprove()) {
                if (!a.getTools().contains(name)) {
                    c.error(
                            a.getLine(),
                            who,
                            "approve: " + name + " is not one of its tools " + a.getTools());
                }
            }
            for (String name : a.getTools()) {
                for (io.github.llm4j.loom.ast.ToolDef def : host.script().getTools()) {
                    if (!def.getName().equals(name)) continue;
                    boolean approved = a.isApproveAll() || a.getApprove().contains(name);
                    String problem = host.agentToolProblem(def, a.getName(), approved);
                    if (problem != null) c.error(a.getLine(), who, problem);
                }
            }
            if ((a.isApproveAll() || !a.getApprove().isEmpty())
                    && !c.context().hasHumanInterface()) {
                c.error(
                        a.getLine(),
                        who,
                        "approve needs someone to ask: set a HumanInterface (weave provides the console)");
            }
        }
    }
}
