package io.github.llm4j.tantrik.console.model;

/**
 * Uniform error body returned for all 4xx and 5xx responses.
 * Requirement 8.1
 */
public record ErrorResponse(String error) {}
