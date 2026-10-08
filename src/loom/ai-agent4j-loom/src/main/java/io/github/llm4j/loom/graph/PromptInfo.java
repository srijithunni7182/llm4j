package io.github.llm4j.loom.graph;

/**
 * The prompt an agent runs, for the details card and the step's chip.
 *
 * @param ref the reference as written ({@code researcher} or {@code researcher@v2})
 * @param version the version that runs, or {@code null} when the reference does not resolve
 * @param file the prompt file, or {@code null} for a registry that is not made of files or an unresolved one
 */
public record PromptInfo(String ref, String version, String file) {

    /** {@code researcher@v2}, or the reference as written when it does not resolve. */
    public String label() {
        if (version == null) {
            return ref;
        }
        int at = ref.indexOf('@');
        return (at < 0 ? ref : ref.substring(0, at)) + "@" + version;
    }
}
