package io.github.llm4j.provider;

import io.github.llm4j.grounding.GroundingOption;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.provider.openai.ResponsesDialect;
import io.github.llm4j.provider.openai.Vendor;
import java.util.Optional;
import java.util.Set;

/**
 * What web-search grounding a kind of provider can do, known without a key or a network call, for
 * layers that must check a configuration before running it (Loom's {@code weave check}). The answer
 * is the same one the constructed provider gives from {@link LLMProvider#groundingOptions()}.
 */
public final class GroundingCapabilities {

    /** No grounding at all. */
    public static final Set<GroundingOption> NONE = Set.of();

    private GroundingCapabilities() {}

    /**
     * The settings of a grounding that a provider of this kind honours, or empty when it cannot
     * ground. {@code kind} is the name used in a script ({@code gemini}, {@code anthropic}, {@code
     * perplexity}, {@code openai}, {@code xai}, {@code ollama}, …); {@code responsesApi} says
     * whether OpenAI or xAI is used through their Responses API ({@code api: responses}), which is
     * where their search lives.
     */
    public static Optional<Set<GroundingOption>> options(String kind, boolean responsesApi) {
        Set<GroundingOption> found =
                switch (kind) {
                    case "gemini" -> GoogleProvider.GROUNDING_OPTIONS;
                    case "anthropic" -> AnthropicProvider.GROUNDING_OPTIONS;
                    case "perplexity" -> ResponsesDialect.PERPLEXITY.grounding().options();
                    case "openai" -> responsesApi
                            ? ResponsesDialect.OPENAI.grounding().options()
                            : NONE;
                    case "xai" -> responsesApi ? ResponsesDialect.XAI.grounding().options() : NONE;
                    default -> NONE;
                };
        return found.isEmpty() ? Optional.empty() : Optional.of(found);
    }

    /** True when {@code kind} is one of the vendors that exposes a Responses API. */
    public static boolean hasResponsesApi(String kind) {
        return Vendor.ofKind(kind).filter(v -> v == Vendor.OPENAI || v == Vendor.XAI).isPresent();
    }
}
