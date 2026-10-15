package io.github.llm4j.context;

import io.github.llm4j.LLMClient;
import java.util.Optional;

/** What a {@link ContextCompactorProvider} may need that a name and some text options cannot carry. */
public record ProviderContext(LLMClient client) {

    public static ProviderContext none() {
        return new ProviderContext(null);
    }

    public static ProviderContext of(LLMClient client) {
        return new ProviderContext(client);
    }

    /** The model client a strategy that calls a model should use, if one was given. */
    public Optional<LLMClient> llmClient() {
        return Optional.ofNullable(client);
    }
}
