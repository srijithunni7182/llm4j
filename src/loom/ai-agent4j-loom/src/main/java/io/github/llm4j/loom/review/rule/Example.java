package io.github.llm4j.loom.review.rule;

/**
 * A script a rule is tested on: either a file in the pack's {@code examples} folder or an inline
 * snippet. {@code expect} is the number of findings the rule must report for it (a {@code fires}
 * example needs at least one, a {@code quiet} example exactly none).
 */
public record Example(String file, String inline, Integer expect) {}
