package io.github.llm4j.eval.assertions;

import io.github.llm4j.eval.export.WorkflowTrace;

/** Entry point for trajectory assertions over a {@link WorkflowTrace}. */
public final class WorkflowAssertions {

    private WorkflowAssertions() {}

    public static WorkflowTraceAssert assertThat(WorkflowTrace actual) {
        io.github.llm4j.eval.export.EvalRun.get().recordWorkflowTrace(actual);
        return new WorkflowTraceAssert(actual);
    }
}
