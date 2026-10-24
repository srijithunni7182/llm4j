package io.github.llm4j.loom.review.engine;

/**
 * Two rules that can describe the same cause at the same place: {@code specific} says it better, so
 * when both fire together {@code general} is folded into it and shown only as a related id, never
 * as a second line saying the same thing.
 *
 * @param specific the rule that stays in the list
 * @param general the rule that is folded into it
 */
public record Overlap(String specific, String general) {}
