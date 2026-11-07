package io.github.llm4j.a2a;

import io.github.llm4j.model.MediaKind;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A file received from outside and accepted by the sink.
 *
 * @param path where it was written, under {@code received/<run id>/} in the media root
 * @param kind what the bytes are
 * @param mediaType the type decided from the bytes
 * @param size the size in bytes
 * @param sha256 the SHA-256 of the content, in hex
 * @param label the sender's name for it; only a label
 */
public record ReceivedFile(
        Path path, MediaKind kind, String mediaType, long size, String sha256, String label) {
    /** Requires every component. */
    public ReceivedFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(sha256, "sha256");
        label = label == null ? "" : label;
    }
}
