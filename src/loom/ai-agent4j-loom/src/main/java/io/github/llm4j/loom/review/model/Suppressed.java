package io.github.llm4j.loom.review.model;

/**
 * A finding the project set aside, with the place and reason it gave. It is listed in the report so
 * that nothing is hidden.
 */
public record Suppressed(Finding finding, String place, String reason) {}
