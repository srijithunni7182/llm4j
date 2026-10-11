package io.github.llm4j.agent.task;

import io.github.llm4j.LLMClient;
import java.util.Map;

/** The plain {@link TaskContext}: three immutable values, two strings and, when the step names a model, a client for it. */
record SimpleTaskContext(Map<String, Object> args, Map<String, Object> variables, String stepId, String idempotencyKey, LLMClient model)
        implements TaskContext {

    @Override
    public LLMClient llm() {
        if (model == null) {
            throw new TaskNotPerformed("this step has no model: write model \"...\" on the run statement (in a test, pass a client to TaskContext.of)");
        }
        return model;
    }
}
