package io.github.llm4j.loom.runtime;

import java.util.Map;

/**
 * Interface for the Loom variable storage context. This allows different engines to implement their
 * own state management.
 */
public interface VariableContext {
    void setVariable(String name, Object value);

    Object getVariable(String name);

    Map<String, Object> getAll();

    /**
     * Forgets a variable set in this context (a rewind goes back to the variables as they were).
     */
    default void removeVariable(String name) {
        throw new UnsupportedOperationException("this context can't forget variables");
    }

    /**
     * What the runtime recorded about where {@code name} (a variable, or a {@code var.field}
     * path) came from, or null when nothing was recorded. The record is opaque here: it is kept
     * beside the values, never inside them, so hosts that read {@link #getAll()} see plain values
     * (docs/specs/26-policy-boundary, R15). A context that keeps none answers null, which the
     * runtime reads as "model-written and unchecked".
     */
    default Object originOf(String name) {
        return null;
    }

    /** Records where {@code name} came from; see {@link #originOf}. */
    default void setOrigin(String name, Object origin) {}

    /** Forgets what is recorded for {@code name} and for every path under it. */
    default void clearOrigin(String name) {}

    /** Creates a new child context that has this as its parent. */
    VariableContext pushFrame();

    /** Returns the parent context, or null if this is the root. */
    VariableContext popFrame();
}
