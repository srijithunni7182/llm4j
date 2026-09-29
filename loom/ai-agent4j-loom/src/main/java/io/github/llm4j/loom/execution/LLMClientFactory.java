package io.github.llm4j.loom.execution;

import io.github.llm4j.LLMClient;

/** Creates the chat clients agents use. Hosts replace it to add providers, or with mocks in tests. */
public interface LLMClientFactory {

    /** A client for a model named in a script, e.g. {@code gemini-2.5-flash} or {@code ollama/llama3}. */
    LLMClient createClient(String modelName);

    /**
     * A client for {@code model} at a provider the script declared ({@code model: "Box/llama3"}).
     * The default builds the real provider.
     */
    default LLMClient createClient(ProviderSpec provider, String model) {
        return DefaultLLMClientFactory.forProvider(provider, model);
    }

    /**
     * Why {@link #createClient(String)} can't serve {@code modelName} (unknown model, missing key), or
     * null if it can. Checked at load time. The default trusts the factory with every name.
     */
    default String problem(String modelName) {
        return null;
    }
}
