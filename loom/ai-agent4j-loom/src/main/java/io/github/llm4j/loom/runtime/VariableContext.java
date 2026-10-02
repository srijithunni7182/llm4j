package io.github.llm4j.loom.runtime;

import java.util.Map;

/**
 * Interface for the Loom variable storage context.
 * This allows different engines to implement their own state management.
 */
public interface VariableContext {
    void setVariable(String name, Object value);
    Object getVariable(String name);
    Map<String, Object> getAll();

    /** Forgets a variable set in this context (a rewind goes back to the variables as they were). */
    default void removeVariable(String name) {
        throw new UnsupportedOperationException("this context can't forget variables");
    }

    /** Creates a new child context that has this as its parent. */
    VariableContext pushFrame();
    /** Returns the parent context, or null if this is the root. */
    VariableContext popFrame();
}
