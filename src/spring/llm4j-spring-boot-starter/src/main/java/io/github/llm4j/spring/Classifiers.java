package io.github.llm4j.spring;

import io.github.llm4j.classify.Classifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every decision-model backend, by name, in the order declared ({@code llm4j.classifiers.<name>}).
 * Each is a {@link Classifier}: ask it typed questions and read typed answers.
 */
public final class Classifiers {

    private final Map<String, Classifier> classifiers;

    Classifiers(Map<String, Classifier> classifiers) {
        this.classifiers = new LinkedHashMap<>(classifiers);
    }

    public List<String> names() {
        return new ArrayList<>(classifiers.keySet());
    }

    /**
     * @throws IllegalArgumentException naming the configured classifiers when {@code name} is not
     *     one
     */
    public Classifier get(String name) {
        Classifier c = classifiers.get(name);
        if (c == null)
            throw new IllegalArgumentException(
                    "no llm4j classifier named "
                            + name
                            + "; configured: "
                            + String.join(", ", classifiers.keySet()));
        return c;
    }
}
