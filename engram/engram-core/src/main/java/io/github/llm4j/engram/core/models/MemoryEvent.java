package io.github.llm4j.engram.core.models;

import java.util.List;

public record MemoryEvent(
    String content,
    String type, // DECISION, CONSTRAINT, OUTCOME, PENDING, ARCH_FACT, ERROR_PATTERN
    double importance,
    String topicKey,
    List<String> tags
) {}
