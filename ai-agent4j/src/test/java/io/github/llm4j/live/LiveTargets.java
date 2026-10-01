package io.github.llm4j.live;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.budget.Budget;
import io.github.llm4j.budget.BudgetedLLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.provider.ollama.OllamaProvider;
import io.github.llm4j.provider.sarvam.SarvamChatProvider;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The real provider/model pairs the live suite can use, from whichever credentials are set:
 *
 * <ul>
 *   <li>Anthropic: {@code ANTHROPIC_API_KEY}; models {@code ANTHROPIC_TEST_MODELS} (default
 *       claude-opus-5-5,claude-haiku-4-5)
 *   <li>Gemini: {@code GEMINI_API_KEY}; {@code GEMINI_TEST_MODELS} (default gemini-2.5-flash)
 *   <li>Sarvam: {@code SARVAM_API_KEY}; {@code SARVAM_TEST_MODELS} (default sarvam-m)
 *   <li>Ollama: {@code OLLAMA_BASE_URL} reachable; {@code OLLAMA_TEST_MODELS} (default llama3.2)
 * </ul>
 *
 * Every client is metered against a 60,000-token budget per provider, so a run can't overspend.
 */
public final class LiveTargets {

    public static final long TOKENS_PER_PROVIDER = 60_000;
    private static final Map<String, Budget> BUDGETS = new ConcurrentHashMap<>();

    /** One provider and model to test against. */
    public record Target(String provider, String model, Function<String, LLMProvider> factory) {
        public LLMProvider provider(String apiKeyOverride) {
            return factory.apply(apiKeyOverride);
        }

        /** A metered client (the provider's shared budget). */
        public LLMClient client() {
            return BudgetedLLMClient.builder(new DefaultLLMClient(factory.apply(null))).budget(budget(provider)).build();
        }

        @Override
        public String toString() {
            return provider + ":" + model;
        }
    }

    private LiveTargets() {}

    public static Budget budget(String provider) {
        return BUDGETS.computeIfAbsent(provider, p -> Budget.builder().name("live " + p).tokens(TOKENS_PER_PROVIDER).build());
    }

    static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v.strip();
    }

    static List<String> models(String variable, String defaults) {
        String v = env(variable);
        return Arrays.stream((v != null ? v : defaults).split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    public static List<Target> all() {
        List<Target> out = new ArrayList<>();
        String anthropic = env("ANTHROPIC_API_KEY");
        if (anthropic != null) {
            for (String m : models("ANTHROPIC_TEST_MODELS", "claude-opus-5-5,claude-haiku-4-5")) {
                out.add(new Target("anthropic", m, key -> new AnthropicProvider(config(key != null ? key : anthropic, env("ANTHROPIC_BASE_URL"), m))));
            }
        }
        String gemini = env("GEMINI_API_KEY");
        if (gemini != null) {
            for (String m : models("GEMINI_TEST_MODELS", "gemini-2.5-flash")) {
                out.add(new Target("gemini", m, key -> new GoogleProvider(config(key != null ? key : gemini, null, m))));
            }
        }
        String sarvam = env("SARVAM_API_KEY");
        if (sarvam != null) {
            for (String m : models("SARVAM_TEST_MODELS", "sarvam-m")) {
                out.add(new Target("sarvam", m, key -> new SarvamChatProvider(config(key != null ? key : sarvam, env("SARVAM_BASE_URL"), m))));
            }
        }
        String ollama = env("OLLAMA_BASE_URL");
        if (ollama != null && reachable(ollama)) {
            String api = ollama.replaceAll("/+$", "").endsWith("/api") ? ollama : ollama.replaceAll("/+$", "") + "/api";
            for (String m : models("OLLAMA_TEST_MODELS", "llama3.2")) {
                out.add(new Target("ollama", m, key -> new OllamaProvider(config(null, api, m))));
            }
        }
        return out;
    }

    static LLMConfig config(String key, String baseUrl, String model) {
        LLMConfig.Builder b = LLMConfig.builder().defaultModel(model).timeout(java.time.Duration.ofSeconds(120));
        if (key != null) b.apiKey(key);
        if (baseUrl != null) b.baseUrl(baseUrl);
        return b.build();
    }

    private static boolean reachable(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            return c.getResponseCode() > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
