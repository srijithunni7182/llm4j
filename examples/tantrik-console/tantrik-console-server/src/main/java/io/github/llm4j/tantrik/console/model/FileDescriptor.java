package io.github.llm4j.tantrik.console.model;

import java.time.Instant;

/**
 * Describes a Loom script file on the server.
 *
 * @param path         relative path from the configured loom-scripts root directory
 * @param name         filename (e.g. "main.loom")
 * @param lastModified last-modified timestamp (serialised as ISO-8601 by Jackson)
 */
public record FileDescriptor(String path, String name, Instant lastModified) {}
