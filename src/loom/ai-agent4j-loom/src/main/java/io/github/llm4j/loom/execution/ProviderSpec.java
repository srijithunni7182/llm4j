package io.github.llm4j.loom.execution;

/**
 * A provider declared in a script ({@code provider Name { use: … base_url: … api_key: env.X }}),
 * with environment references already resolved. {@link #toString()} never shows the key.
 *
 * @param kind {@code gemini}, {@code ollama}, {@code sarvam}, {@code anthropic}, {@code openai},
 *     {@code xai}, {@code kimi}, {@code qwen}, {@code perplexity}, {@code perplexity_router},
 *     {@code openai_compatible}, or {@code jev} (which answers typed questions for an {@code
 *     intentclassifier} and does not chat)
 * @param baseUrl the endpoint, or null for the provider's default
 * @param apiKey the key, or null (Ollama needs none); used when {@code apiKeyRef} is null
 * @param apiKeyRef the key held in a secret store, fetched for each request (and bound to the
 *     secret's allowed hosts), or null
 * @param options the other settings the script gave, resolved ({@code api}, {@code header}, {@code
 *     query}); empty when none
 */
public record ProviderSpec(
        String name,
        String kind,
        String baseUrl,
        String apiKey,
        io.github.llm4j.secret.SecretRef apiKeyRef,
        java.util.Map<String, String> options) {

    public ProviderSpec {
        options = options == null ? java.util.Map.of() : java.util.Map.copyOf(options);
    }

    public ProviderSpec(
            String name,
            String kind,
            String baseUrl,
            String apiKey,
            io.github.llm4j.secret.SecretRef apiKeyRef) {
        this(name, kind, baseUrl, apiKey, apiKeyRef, null);
    }

    public ProviderSpec(String name, String kind, String baseUrl, String apiKey) {
        this(name, kind, baseUrl, apiKey, null);
    }

    /** The kinds a script can declare: Loom's own, and the OpenAI-family vendors of ai-agent4j. */
    public static final java.util.Set<String> KINDS = kinds();

    private static java.util.Set<String> kinds() {
        java.util.Set<String> kinds =
                new java.util.LinkedHashSet<>(
                        java.util.List.of(
                                "gemini",
                                "anthropic",
                                "ollama",
                                "sarvam",
                                "jev",
                                "perplexity_decisions"));
        for (io.github.llm4j.provider.openai.Vendor v :
                io.github.llm4j.provider.openai.Vendor.values()) kinds.add(v.kind());
        return java.util.Collections.unmodifiableSet(kinds);
    }

    @Override
    public String toString() {
        return "provider " + name + " (" + kind + (baseUrl != null ? " at " + baseUrl : "") + ")";
    }
}
