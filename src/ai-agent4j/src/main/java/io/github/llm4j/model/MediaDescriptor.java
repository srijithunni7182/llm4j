package io.github.llm4j.model;

/**
 * What may be written down about a piece of {@link Media}: its kind, type, size, hash and display
 * name. This is what a journal, a trace or a report carries in place of the bytes, and what lets a
 * replay check that the file it reloads is the one that was sent.
 *
 * @param kind what the media is
 * @param mediaType the type decided from its bytes, such as {@code image/png}
 * @param bytes its size in bytes
 * @param sha256 the SHA-256 of its bytes, in lower-case hex
 * @param name a display name: a file name with no directory
 */
public record MediaDescriptor(
        MediaKind kind, String mediaType, long bytes, String sha256, String name) {}
