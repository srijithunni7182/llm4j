package io.github.llm4j.loom.host;

/** Whether an answer was taken, and if not, why. */
public record AnswerResult(boolean recorded, String message) { }
