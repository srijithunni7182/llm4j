package io.github.llm4j.eval.dataset.synthesis;

/** A transformation applied to a base question to make it harder or more varied. */
public enum Evolution {
    REASONING(
            "Rewrite the question so answering it requires multi-step reasoning over the source."),
    MULTI_CONTEXT(
            "Rewrite the question so answering it requires combining information from ALL of the"
                    + " provided sources."),
    CONCRETIZING(
            "Rewrite the question to be more specific and concrete, naming particular details."),
    CONSTRAINED(
            "Rewrite the question to add a constraint (a format, a limit, or a condition) that the"
                    + " answer must respect."),
    COMPARATIVE("Rewrite the question to ask for a comparison between two things in the source.");

    private final String instruction;

    Evolution(String instruction) {
        this.instruction = instruction;
    }

    String instruction() {
        return instruction;
    }
}
