package io.github.llm4j.loom.execution;

import java.util.Locale;

/**
 * The kind of provider a built-in model name belongs to ({@code gemini}, {@code anthropic}, {@code
 * perplexity}, …), by the same rules {@link DefaultLLMClientFactory} follows to pick a client, for
 * checks that need to know what a model can do without making a client. A model of a provider
 * declared in the script is not here: its kind is the declaration's {@code use:}.
 */
final class ModelKinds {

    private ModelKinds() {}

    /** The kind, or null for a name no built-in provider serves. */
    static String kindOf(String model) {
        String m = model.toLowerCase(Locale.ROOT);
        if (m.startsWith("jev/") || m.startsWith("jev-")) return "jev";
        if (m.startsWith("sarvam/")) return "sarvam";
        if (m.startsWith("anthropic/") || m.startsWith("claude-")) return "anthropic";
        var vendor = io.github.llm4j.provider.openai.Vendor.ofModel(model);
        if (vendor.isPresent()) return vendor.get().vendor().kind();
        if (m.contains("gemini")) return "gemini";
        if (m.startsWith("ollama/")
                || m.contains("llama")
                || m.contains("gemma")
                || m.contains("mistral")) return "ollama";
        return null;
    }
}
