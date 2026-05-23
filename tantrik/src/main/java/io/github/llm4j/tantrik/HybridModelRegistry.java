package io.github.llm4j.tantrik;

import java.util.Locale;

/**
 * Routes a model name string to the appropriate provider back-end.
 *
 * <p>Routing rules (evaluated in order):
 * <ol>
 *   <li>{@code gemini-*} or {@code google/*} → {@link ModelRoute#CLOUD_GEMINI}</li>
 *   <li>{@code sarvam-*} or {@code mayura*} or {@code bulbul*} or {@code saaras*}
 *       → {@link ModelRoute#CLOUD_SARVAM}</li>
 *   <li>{@code ollama/*} or names containing {@code llama}, {@code mistral}, {@code gemma}, or
 *       {@code phi} → {@link ModelRoute#LOCAL_OLLAMA}</li>
 * </ol>
 */
public class HybridModelRegistry {

    public enum ModelRoute {
        /** Google Gemini API (cloud). */
        CLOUD_GEMINI,
        /** Sarvam AI API (cloud, Indian-sovereign). */
        CLOUD_SARVAM,
        /** Local Ollama runtime. */
        LOCAL_OLLAMA
    }

    public ModelRoute resolveRoute(String modelName) {
        String normalized = normalizeModelName(modelName);

        // ── Google Gemini ──────────────────────────────────────────────────────
        if (normalized.startsWith("gemini-") || normalized.startsWith("google/")) {
            return ModelRoute.CLOUD_GEMINI;
        }

        // ── Sarvam AI (foundational + specialist models) ───────────────────────
        if (normalized.startsWith("sarvam-")
                || normalized.startsWith("mayura")
                || normalized.startsWith("bulbul")
                || normalized.startsWith("saaras")) {
            return ModelRoute.CLOUD_SARVAM;
        }

        // ── Local Ollama ───────────────────────────────────────────────────────
        if (normalized.startsWith("ollama/")
                || normalized.contains("llama")
                || normalized.contains("mistral")
                || normalized.contains("gemma")
                || normalized.contains("phi")) {
            return ModelRoute.LOCAL_OLLAMA;
        }

        throw new IllegalArgumentException(
                "Unsupported model routing for '" + modelName + "'. "
                        + "Supported prefixes: gemini-*, google/*, sarvam-*, mayura*, "
                        + "bulbul*, saaras*, ollama/<model>.");
    }

    public String normalizeModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("Model name cannot be null or blank.");
        }
        return modelName.trim().toLowerCase(Locale.ROOT);
    }
}
