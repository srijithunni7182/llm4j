package io.github.llm4j.classify;

/**
 * Told about every call of a classifier wrapped with {@link Classifier#observed(ClassifyListener)}.
 */
@FunctionalInterface
public interface ClassifyListener {

    void onClassify(ClassifyEvent event);
}
