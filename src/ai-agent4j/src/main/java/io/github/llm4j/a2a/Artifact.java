package io.github.llm4j.a2a;

import java.util.List;
import java.util.Objects;

/**
 * An output of a remote task.
 *
 * @param id the artifact id
 * @param name a display name, possibly empty
 * @param parts the content
 */
public record Artifact(String id, String name, List<Part> parts) {
    /** Requires an id; copies the parts. */
    public Artifact {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("artifact id must not be blank");
        }
        name = name == null ? "" : name;
        Objects.requireNonNull(parts, "parts");
        parts = List.copyOf(parts);
    }
}
