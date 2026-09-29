package io.github.llm4j.loom.execution;

/**
 * A provider declared in a script ({@code provider Name { use: … base_url: … api_key: env.X }}), with
 * environment references already resolved. {@link #toString()} never shows the key.
 *
 * @param kind {@code gemini}, {@code ollama}, {@code sarvam} or {@code anthropic}
 * @param baseUrl the endpoint, or null for the provider's default
 * @param apiKey the key, or null (Ollama needs none)
 */
public record ProviderSpec(String name, String kind, String baseUrl, String apiKey) {

    public static final java.util.Set<String> KINDS = java.util.Set.of("gemini", "ollama", "sarvam", "anthropic");

    @Override
    public String toString() {
        return "provider " + name + " (" + kind + (baseUrl != null ? " at " + baseUrl : "") + ")";
    }
}
