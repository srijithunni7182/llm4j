package io.github.llm4j.loom.inspect.wire;

/** What the process did with a {@link Command}: taken, or refused with the reason. */
public record CommandResult(String id, boolean accepted, String message) { }
