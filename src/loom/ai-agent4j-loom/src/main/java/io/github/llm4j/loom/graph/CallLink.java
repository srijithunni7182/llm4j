package io.github.llm4j.loom.graph;

/** The workflow a {@code call} names and, once resolved, the file that defines it ({@code null} until then). */
public record CallLink(String workflow, String file) {

    public CallLink resolvedIn(String definingFile) {
        return new CallLink(workflow, definingFile);
    }
}
