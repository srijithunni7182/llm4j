package io.github.llm4j.loom.execution;

import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.provider.ollama.OllamaProvider;
import io.github.llm4j.provider.sarvam.SarvamChatProvider;
import io.github.llm4j.secret.SecretRef;
import io.github.llm4j.secret.SecretStore;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Resolves model names to providers, with keys and endpoints from the environment:
 *
 * <ul>
 *   <li>{@code gemini-*} (any name containing {@code gemini}): Google, {@code GEMINI_API_KEY};
 *   <li>{@code ollama/<model>}, or a name containing llama, gemma or mistral: Ollama at
 *       {@code OLLAMA_BASE_URL} (default {@code http://localhost:11434});
 *   <li>{@code sarvam/<model>}: Sarvam, {@code SARVAM_API_KEY}, optionally {@code SARVAM_BASE_URL};
 *   <li>{@code anthropic/<model>} or {@code claude-*}: Anthropic, {@code ANTHROPIC_API_KEY}, optionally
 *       {@code ANTHROPIC_BASE_URL}.
 * </ul>
 */
public class DefaultLLMClientFactory implements LLMClientFactory {

    private final Function<String, String> env;
    private final SecretStore secrets;

    public DefaultLLMClientFactory() {
        this(System::getenv);
    }

    /** @param env where keys and endpoints are read (the process environment by default) */
    public DefaultLLMClientFactory(Function<String, String> env) {
        this(env, null);
    }

    /**
     * @param env where endpoints, and keys not found in the store, are read
     * @param secrets where keys are looked up first, by the name of the variable they replace ({@code GEMINI_API_KEY}); null for none. A key found
     *     there is fetched for each request and keeps the host binding it was stored with.
     */
    public DefaultLLMClientFactory(Function<String, String> env, SecretStore secrets) {
        this.env = env;
        this.secrets = secrets;
    }

    /** The key called {@code name}: from the store when it has one, else from the environment (null when neither does). */
    private ProviderSpec spec(String provider, String baseUrl, String name) {
        if (secrets != null && secrets.contains(name)) return new ProviderSpec(provider, provider, baseUrl, null, SecretRef.of(secrets, name));
        String key = env(name);
        return key == null ? null : new ProviderSpec(provider, provider, baseUrl, key);
    }

    private boolean hasKey(String name) {
        return (secrets != null && secrets.contains(name)) || env(name) != null;
    }

    private enum Kind { GEMINI, OLLAMA, SARVAM, ANTHROPIC }

    private static Kind kindOf(String modelName) {
        String m = modelName.toLowerCase(Locale.ROOT);
        if (m.startsWith("sarvam/")) return Kind.SARVAM;
        if (m.startsWith("anthropic/") || m.startsWith("claude-")) return Kind.ANTHROPIC;
        if (m.contains("gemini")) return Kind.GEMINI;
        if (m.startsWith("ollama/") || m.contains("llama") || m.contains("gemma") || m.contains("mistral")) return Kind.OLLAMA;
        return null;
    }

    private String env(String name) {
        String v = env.apply(name);
        return v == null || v.isBlank() ? null : v;
    }

    private boolean hasGeminiKey() {
        return hasKey("GEMINI_API_KEY") || System.getProperty("google.api.key") != null;
    }

    @Override
    public String problem(String modelName) {
        if (modelName == null || modelName.isBlank()) return "no model given";
        Kind kind = kindOf(modelName);
        if (kind == null) {
            return "unknown model \"" + modelName + "\": use gemini-…, claude-… (or anthropic/<model>), ollama/<model>, sarvam/<model>, "
                    + "or a provider declared in the script (provider Name { use: … }, then \"Name/<model>\")";
        }
        if (kind == Kind.GEMINI && !hasGeminiKey()) return "model " + modelName + " needs GEMINI_API_KEY in the environment or the secret store";
        if (kind == Kind.SARVAM && !hasKey("SARVAM_API_KEY")) return "model " + modelName + " needs SARVAM_API_KEY in the environment or the secret store";
        if (kind == Kind.ANTHROPIC && !hasKey("ANTHROPIC_API_KEY")) return "model " + modelName + " needs ANTHROPIC_API_KEY in the environment or the secret store";
        return null;
    }

    @Override
    public LLMClient createClient(String modelName) {
        Kind kind = kindOf(modelName);
        if (kind == null) throw new IllegalArgumentException(problem(modelName));
        return switch (kind) {
            case GEMINI -> {
                ProviderSpec spec = spec("gemini", null, "GEMINI_API_KEY");
                if (spec == null && System.getProperty("google.api.key") != null) spec = new ProviderSpec("gemini", "gemini", null, System.getProperty("google.api.key"));
                if (spec == null) throw new IllegalStateException("GEMINI_API_KEY (an environment variable or a secret) is required for model: " + modelName);
                yield forProvider(spec, modelName);
            }
            case OLLAMA -> forProvider(new ProviderSpec("ollama", "ollama",
                    env("OLLAMA_BASE_URL") != null ? env("OLLAMA_BASE_URL") : "http://localhost:11434", null),
                    modelName.toLowerCase(Locale.ROOT).startsWith("ollama/") ? modelName.substring("ollama/".length()) : modelName);
            case SARVAM -> {
                ProviderSpec spec = spec("sarvam", env("SARVAM_BASE_URL"), "SARVAM_API_KEY");
                if (spec == null) throw new IllegalStateException("SARVAM_API_KEY (an environment variable or a secret) is required for model: " + modelName);
                yield forProvider(spec, modelName.substring("sarvam/".length()));
            }
            case ANTHROPIC -> {
                ProviderSpec spec = spec("anthropic", env("ANTHROPIC_BASE_URL"), "ANTHROPIC_API_KEY");
                if (spec == null) throw new IllegalStateException("ANTHROPIC_API_KEY (an environment variable or a secret) is required for model: " + modelName);
                yield forProvider(spec, modelName.startsWith("anthropic/") ? modelName.substring("anthropic/".length()) : modelName);
            }
        };
    }

    /** The real client for a model at a provider. */
    public static LLMClient forProvider(ProviderSpec spec, String model) {
        LLMConfig.Builder config = LLMConfig.builder().defaultModel(model);
        if (spec.baseUrl() != null) config.baseUrl(spec.kind().equals("ollama") ? ollamaApi(spec.baseUrl()) : spec.baseUrl());
        if (spec.apiKeyRef() != null) config.apiKey(spec.apiKeyRef());
        else if (spec.apiKey() != null) config.apiKey(spec.apiKey());
        LLMProvider provider = switch (spec.kind()) {
            case "gemini" -> new GoogleProvider(config.build());
            case "ollama" -> new OllamaProvider(config.build());
            case "sarvam" -> new SarvamChatProvider(config.build());
            case "anthropic" -> new io.github.llm4j.provider.anthropic.AnthropicProvider(config.build());
            default -> throw new IllegalArgumentException("unknown provider kind " + spec.kind() + "; use one of " + ProviderSpec.KINDS);
        };
        return wrap(provider);
    }

    /** Ollama's API lives under /api; people write the server's address ({@code http://host:11434}). */
    static String ollamaApi(String baseUrl) {
        String b = baseUrl.replaceAll("/+$", "");
        return b.endsWith("/api") ? b : b + "/api";
    }

    private static LLMClient wrap(LLMProvider provider) {
        return new io.github.llm4j.DefaultLLMClient(provider);
    }
}
