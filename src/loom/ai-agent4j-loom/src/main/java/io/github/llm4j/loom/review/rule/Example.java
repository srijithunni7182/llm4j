package io.github.llm4j.loom.review.rule;

import java.util.Map;

/**
 * A script a rule is tested on: either a file in the pack's {@code examples} folder or an inline
 * snippet. {@code expect} is the number of findings the rule must report for it (a {@code fires}
 * example needs at least one, a {@code quiet} example exactly none). {@code settings} are the
 * project settings the example is reviewed under, such as {@code data.top-models}, for a rule that
 * needs a declared fact. {@code companions} maps a path in the folder the example is reviewed in
 * (such as {@code prompts/writer/v2.md}) to a file of the pack that holds its text, for a rule
 * about files beside the script.
 */
public record Example(
        String file,
        String inline,
        Integer expect,
        Map<String, String> settings,
        Map<String, String> companions) {

    public Example {
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        companions = companions == null ? Map.of() : Map.copyOf(companions);
    }

    public Example(String file, String inline, Integer expect, Map<String, String> settings) {
        this(file, inline, expect, settings, Map.of());
    }

    public Example(String file, String inline, Integer expect) {
        this(file, inline, expect, Map.of(), Map.of());
    }
}
