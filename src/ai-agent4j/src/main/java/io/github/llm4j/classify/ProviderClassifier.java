package io.github.llm4j.classify;

import io.github.llm4j.provider.ClassificationProvider;
import java.util.Objects;

/**
 * A {@link ClassificationProvider} as a {@link Classifier}: validates the provider once and every
 * result it returns.
 */
final class ProviderClassifier implements Classifier {

    private final ClassificationProvider provider;

    ProviderClassifier(ClassificationProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider cannot be null");
        provider.validate();
    }

    @Override
    public ClassifyResult classify(ClassifyRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        return ClassifyValidation.check(request, provider.classify(request));
    }
}
