package io.github.llm4j.loom.execution;

import io.github.llm4j.classify.Classifier;

/**
 * Creates the classifiers {@code intentclassify} steps use. Hosts replace it to add backends, or
 * with a scripted classifier in tests. The default (see {@link HarnessExecutor}) serves Jev, from
 * {@code JEV_API_KEY} or a provider declared with {@code use: jev}, and any chat model the {@link
 * LLMClientFactory} can serve, through {@link io.github.llm4j.classify.LlmClassifier}.
 */
@FunctionalInterface
public interface ClassifierFactory {

    /**
     * A classifier for the {@code model} an {@code intentclassifier} names ({@code Jev/jev-latest},
     * {@code gemini-2.5-flash}, {@code Box/llama3}).
     *
     * @param samples the classifier's {@code samples:} setting (1 when it has none); a backend that
     *     answers the same every time may ignore it
     */
    Classifier create(String model, int samples);

    /**
     * Why {@link #create} can't serve {@code model} (a missing key, an unknown model), or null.
     * Checked at load time; the default trusts the factory.
     */
    default String problem(String model) {
        return null;
    }
}
