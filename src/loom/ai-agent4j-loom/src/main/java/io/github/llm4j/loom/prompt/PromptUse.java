package io.github.llm4j.loom.prompt;

import java.nio.file.Path;

/**
 * The prompt an agent would run: what the audit, the graph and a run record show. Never the text itself, only a short hash of it.
 *
 * @param version null when the reference does not resolve
 * @param hash    the first 12 hex digits of the SHA-256 of the text; null when it does not resolve
 */
public record PromptUse(String agent, int line, String reference, String id, String version, String hash, Path file) {

    public boolean resolved() {
        return version != null;
    }

    /** {@code researcher@v2}, or the reference as written when it does not resolve. */
    public String label() {
        return resolved() ? id + "@" + version : reference;
    }
}
