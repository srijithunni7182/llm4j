package io.github.llm4j.grounding;

/** Whether a provider can search the web itself and return the sources it used. */
public enum GroundingSupport {
    /**
     * The provider has no search of its own; a grounded request is refused unless it carries a
     * client-side {@link Grounding#fallback()}.
     */
    NONE,
    /** The provider runs the search inside the model call and reports its sources. */
    NATIVE
}
