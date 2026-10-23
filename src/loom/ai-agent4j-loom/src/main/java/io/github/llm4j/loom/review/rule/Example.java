package io.github.llm4j.loom.review.rule;

import java.util.Map;

/**
 * A script a rule is tested on: either a file in the pack's {@code examples} folder or an inline
 * snippet. {@code expect} is the number of findings the rule must report for it (a {@code fires}
 * example needs at least one, a {@code quiet} example exactly none). {@code settings} are the
 * project settings the example is reviewed under, such as {@code data.top-models}, for a rule that
 * needs a declared fact.
 */
public record Example(String file, String inline, Integer expect, Map<String, String> settings) {

    public Example {
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }

    public Example(String file, String inline, Integer expect) {
        this(file, inline, expect, Map.of());
    }
}
