package io.github.llm4j.classify;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Reports every call of a classifier to a listener, then returns the result or rethrows. A listener
 * that throws never breaks the call.
 */
final class ObservedClassifier implements Classifier {

    private final Classifier delegate;
    private final ClassifyListener listener;

    ObservedClassifier(Classifier delegate, ClassifyListener listener) {
        this.delegate = Objects.requireNonNull(delegate, "classifier cannot be null");
        this.listener = Objects.requireNonNull(listener, "listener cannot be null");
    }

    @Override
    public ClassifyResult classify(ClassifyRequest request) {
        long start = System.nanoTime();
        try {
            ClassifyResult result = delegate.classify(request);
            Map<String, String> labels = new LinkedHashMap<>();
            result.answers()
                    .forEach(
                            (name, a) -> {
                                if (a instanceof ChoiceAnswer c) labels.put(name, c.label());
                            });
            notify(
                    new ClassifyEvent(
                            result.usage(),
                            request.questions().size(),
                            ClassifyEvent.Outcome.OK,
                            null,
                            labels));
            return result;
        } catch (RuntimeException e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            notify(
                    new ClassifyEvent(
                            new ClassifyUsage(0, 0, ms, "", ""),
                            request.questions().size(),
                            ClassifyEvent.Outcome.FAILED,
                            e.getClass().getSimpleName(),
                            Map.of()));
            throw e;
        }
    }

    private void notify(ClassifyEvent event) {
        try {
            listener.onClassify(event);
        } catch (RuntimeException ignored) {
            // an observer must not change the outcome
        }
    }
}
