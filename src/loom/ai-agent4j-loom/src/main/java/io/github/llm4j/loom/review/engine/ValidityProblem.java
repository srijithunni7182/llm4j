package io.github.llm4j.loom.review.engine;

/**
 * One problem the script's own validation reported ({@code weave check}), in a form the review does
 * not need the validator to understand. {@code construct} names what it is about, such as {@code
 * agent Writer}; {@code line} is 0 when it is about the whole script.
 */
public record ValidityProblem(int line, String construct, String message, boolean error) {}
