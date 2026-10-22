package io.github.llm4j.spring;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.provider.ollama.OllamaProvider;
import io.github.llm4j.provider.openai.Vendor;
import io.github.llm4j.provider.sarvam.SarvamChatProvider;
import io.github.llm4j.ratelimit.Sleeper;
import io.github.llm4j.secret.SecretRef;
import io.github.llm4j.secret.SecretStore;
import java.time.Clock;
import java.util.Locale;

/**
 * Builds a provider client from its settings: the same few lines you would write by hand. Building
 * one never contacts the provider.
 */
final class ClientFactory {

    private static final String KNOWN =
            "gemini, anthropic, ollama, sarvam, openai, xai, kimi, qwen, perplexity, perplexity_router or openai_compatible";

    private ClientFactory() {}

    static LLMClient create(
            String name,
            Llm4jProperties.Client c,
            SecretStore secrets,
            Clock clock,
            Sleeper sleeper) {
        String where = "llm4j.clients." + name;
        if (c.provider() == null || c.provider().isBlank()) {
            throw new Llm4jConfigurationException(
                    where + ".provider is not set", "Set it to " + KNOWN + ".");
        }
        String provider = c.provider().trim().toLowerCase(Locale.ROOT).replace('-', '_');
        Vendor vendor = Vendor.ofKind(provider).orElse(null);
        if (vendor == null
                && !java.util.List.of("gemini", "anthropic", "ollama", "sarvam")
                        .contains(provider)) {
            throw new Llm4jConfigurationException(
                    where + ".provider=" + c.provider() + " is not known",
                    "Use " + KNOWN + ", or declare your own LLMClient bean.");
        }
        if (vendor == Vendor.OPENAI_COMPATIBLE && (c.baseUrl() == null || c.baseUrl().isBlank())) {
            throw new Llm4jConfigurationException(
                    where + " (openai_compatible) has no base-url",
                    "Set "
                            + where
                            + ".base-url to the address of the server, for example http://localhost:8000/v1.");
        }
        SecretRef key =
                CredentialProperty.ref(where + ".api-key", c.apiKey(), c.apiKeySecret(), secrets);
        if (key == null
                && !provider.equals("ollama")
                && !(vendor != null && vendor.keyOptional())) {
            throw new Llm4jConfigurationException(
                    where + " (" + provider + ") has no API key",
                    "Set "
                            + where
                            + ".api-key-secret to the name of a secret, for example "
                            + provider.toUpperCase(Locale.ROOT)
                            + "_API_KEY, and configure llm4j.secrets.store.");
        }
        LLMConfig.Builder config =
                LLMConfig.builder().retryPolicy(RetryPolicies.from(c.retry(), clock, sleeper));
        if (c.model() != null) config.defaultModel(c.model());
        if (key != null) config.apiKey(key);
        if (c.baseUrl() != null)
            config.baseUrl(provider.equals("ollama") ? ollamaApi(c.baseUrl()) : c.baseUrl());
        if (c.timeout() != null) config.timeout(c.timeout());
        if (c.connectTimeout() != null) config.connectTimeout(c.connectTimeout());
        LLMConfig built = config.build();
        LLMProvider llm =
                switch (provider) {
                    case "gemini" -> new GoogleProvider(built);
                    case "anthropic" -> c.effort() == null
                            ? new AnthropicProvider(built)
                            : new AnthropicProvider(built, c.effort());
                    case "ollama" -> new OllamaProvider(built);
                    case "sarvam" -> new SarvamChatProvider(built);
                    default -> vendor.create(built, java.util.Map.of());
                };
        return new DefaultLLMClient(llm);
    }

    /** Ollama's API lives under /api; people write the server's address. */
    static String ollamaApi(String baseUrl) {
        String b = baseUrl.replaceAll("/+$", "");
        return b.endsWith("/api") ? b : b + "/api";
    }
}
