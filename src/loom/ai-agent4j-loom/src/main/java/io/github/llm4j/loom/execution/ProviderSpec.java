package io.github.llm4j.loom.execution;

/**
 * A provider declared in a script ({@code provider Name { use: … base_url: … api_key: env.X }}), with
 * environment references already resolved. {@link #toString()} never shows the key.
 *
 * @param kind {@code gemini}, {@code ollama}, {@code sarvam} or {@code anthropic}
 * @param baseUrl the endpoint, or null for the provider's default
 * @param apiKey the key, or null (Ollama needs none); used when {@code apiKeyRef} is null
 * @param apiKeyRef the key held in a secret store, fetched for each request (and bound to the secret's allowed hosts), or null
 */
public record ProviderSpec(String name, String kind, String baseUrl, String apiKey, io.github.llm4j.secret.SecretRef apiKeyRef) {

    public ProviderSpec(String name, String kind, String baseUrl, String apiKey) {
        this(name, kind, baseUrl, apiKey, null);
    }

    public static final java.util.Set<String> KINDS = java.util.Set.of("gemini", "ollama", "sarvam", "anthropic");

    @Override
    public String toString() {
        return "provider " + name + " (" + kind + (baseUrl != null ? " at " + baseUrl : "") + ")";
    }
}
