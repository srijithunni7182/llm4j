package io.github.llm4j.loom.execution;

/**
 * What a workflow produced, as one defined thing used by {@code weave run}, {@code weave eval} and
 * the other ways of running one: the value of its {@code return}; else the text of its last {@code
 * note}; else nothing. A caller that must have a result (a judge) treats {@link Source#NONE} as an
 * error, never as an empty answer to read.
 *
 * @param text the output, never null (empty when there is none)
 * @param source where it came from
 */
public record WorkflowOutput(String text, Source source) {

    /** Where an output came from. */
    public enum Source {
        /** The workflow's {@code return}. */
        RETURN,
        /** The last {@code note} it printed, because it returned nothing. */
        NOTE,
        /** Neither: the workflow ended without a value to show. */
        NONE
    }

    /** The output of a workflow that returned nothing and printed nothing. */
    public static final WorkflowOutput NOTHING = new WorkflowOutput("", Source.NONE);

    public WorkflowOutput {
        text = text == null ? "" : text;
    }

    /** True when there is nothing to show or judge. */
    public boolean isEmpty() {
        return source == Source.NONE || text.isBlank();
    }
}
