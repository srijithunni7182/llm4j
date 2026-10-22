package io.github.llm4j.loom.review.pack;

import java.util.List;

/**
 * A pack could not be loaded; every problem found is listed, so an author fixes them in one pass.
 */
public final class PackException extends RuntimeException {

    private final List<String> problems;

    public PackException(String where, List<String> problems) {
        super(where + ": " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
