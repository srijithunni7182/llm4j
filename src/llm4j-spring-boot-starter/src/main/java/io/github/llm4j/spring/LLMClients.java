package io.github.llm4j.spring;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.provider.ollama.OllamaProvider;
import io.github.llm4j.provider.sarvam.SarvamChatProvider;
import java.util.Locale;

/** Builds the client for {@code llm4j.provider}: the same few lines you would write by hand, in one place. */
final class LLMClients {

    private LLMClients() {}

    static LLMClient create(Llm4jProperties p) {
        String provider = p.getProvider().trim().toLowerCase(Locale.ROOT);
        boolean needsKey = !provider.equals("ollama");
        if (needsKey && (p.getApiKey() == null || p.getApiKey().isBlank())) {
            throw new IllegalStateException("llm4j.provider=" + provider + " needs llm4j.api-key (set it from an environment variable, e.g. llm4j.api-key=${"
                    + provider.toUpperCase(Locale.ROOT) + "_API_KEY})");
        }
        LLMConfig.Builder config = LLMConfig.builder();
        if (p.getModel() != null) config.defaultModel(p.getModel());
        if (p.getApiKey() != null && !p.getApiKey().isBlank()) config.apiKey(p.getApiKey());
        if (p.getBaseUrl() != null) config.baseUrl(provider.equals("ollama") ? ollamaApi(p.getBaseUrl()) : p.getBaseUrl());
        if (p.getTimeout() != null) config.timeout(p.getTimeout());
        if (p.getConnectTimeout() != null) config.connectTimeout(p.getConnectTimeout());
        LLMConfig built = config.build();
        LLMProvider llm = switch (provider) {
            case "gemini" -> new GoogleProvider(built);
            case "anthropic" -> new AnthropicProvider(built);
            case "ollama" -> new OllamaProvider(built);
            case "sarvam" -> new SarvamChatProvider(built);
            default -> throw new IllegalStateException("llm4j.provider=" + p.getProvider() + " is not known; use gemini, anthropic, ollama or sarvam, or declare your own LLMClient bean");
        };
        return new DefaultLLMClient(llm);
    }

    /** Ollama's API lives under /api; people write the server's address. */
    static String ollamaApi(String baseUrl) {
        String b = baseUrl.replaceAll("/+$", "");
        return b.endsWith("/api") ? b : b + "/api";
    }
}
