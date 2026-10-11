package io.github.llm4j.loom.inspect.wire;

/** Sent every few seconds, so a quiet process can be told from a dead one. {@code at} is an ISO-8601 instant. */
public record Heartbeat(String client, String instance, String at) { }
