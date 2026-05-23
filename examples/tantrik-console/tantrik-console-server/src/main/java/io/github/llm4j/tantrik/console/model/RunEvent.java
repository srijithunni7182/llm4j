package io.github.llm4j.tantrik.console.model;

import java.time.Instant;
import java.util.Map;

public record RunEvent(
        String type,
        String message,
        String executionTier,
        Instant timestamp,
        Map<String, Object> metadata
) {}
