package io.github.llm4j.tantrik;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class HybridModelRegistryTest {

    private final HybridModelRegistry registry = new HybridModelRegistry();

    // ── Gemini / Google routes ─────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"gemini-3.5-flash", "gemini-3.1-flash-lite", "google/gemini-pro"})
    void resolvesGeminiVariantsToCloud(String model) {
        assertEquals(HybridModelRegistry.ModelRoute.CLOUD_GEMINI, registry.resolveRoute(model));
    }

    // ── Sarvam routes ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "sarvam-30b",
            "sarvam-105b",
            "sarvam-m",           // legacy — still routes correctly
            "mayura:v1",
            "bulbul:v3",
            "saaras:v3"
    })
    void resolvesSarvamModelsToCloudSarvam(String model) {
        assertEquals(HybridModelRegistry.ModelRoute.CLOUD_SARVAM, registry.resolveRoute(model),
                "Expected CLOUD_SARVAM for model: " + model);
    }

    // ── Ollama / local routes ──────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"ollama/mistral", "ollama/llama3", "phi-3", "gemma:7b"})
    void resolvesLocalModelsToOllama(String model) {
        assertEquals(HybridModelRegistry.ModelRoute.LOCAL_OLLAMA, registry.resolveRoute(model));
    }

    // ── Error cases ────────────────────────────────────────────────────────────

    @Test
    void rejectsUnsupportedModel() {
        assertThrows(IllegalArgumentException.class, () -> registry.resolveRoute("gpt-4.1"));
    }

    @Test
    void rejectsNullModelName() {
        assertThrows(IllegalArgumentException.class, () -> registry.resolveRoute(null));
    }

    @Test
    void rejectsBlankModelName() {
        assertThrows(IllegalArgumentException.class, () -> registry.resolveRoute("   "));
    }

    // ── Normalisation ──────────────────────────────────────────────────────────

    @Test
    void normalisationIsCaseInsensitive() {
        assertEquals(
                HybridModelRegistry.ModelRoute.CLOUD_GEMINI,
                registry.resolveRoute("GEMINI-3.5-Flash"));
    }

    @Test
    void normalisationTrimsWhitespace() {
        assertEquals(
                HybridModelRegistry.ModelRoute.CLOUD_SARVAM,
                registry.resolveRoute("  sarvam-30b  "));
    }
}
