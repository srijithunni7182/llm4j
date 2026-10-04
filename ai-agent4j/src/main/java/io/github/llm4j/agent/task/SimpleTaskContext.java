package io.github.llm4j.agent.task;

import java.util.Map;

/** The plain {@link TaskContext}: three immutable values and two strings. */
record SimpleTaskContext(Map<String, Object> args, Map<String, Object> variables, String stepId, String idempotencyKey)
        implements TaskContext {
}
