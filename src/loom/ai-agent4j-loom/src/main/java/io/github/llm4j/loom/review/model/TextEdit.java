package io.github.llm4j.loom.review.model;

/**
 * One change to a file: replace the range with {@code newText} (an insertion has an empty range).
 */
public record TextEdit(
        String file, int startLine, int startColumn, int endLine, int endColumn, String newText) {}
