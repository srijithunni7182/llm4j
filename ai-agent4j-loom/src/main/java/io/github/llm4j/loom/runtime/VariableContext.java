package io.github.llm4j.loom.runtime;

import java.util.Map;

/**
 * Interface for the Loom variable storage context.
 * This allows different engines to implement their own state management.
 */
public interface VariableContext {
    void setVariable(String name, String value);
    String getVariable(String name);
    Map<String, String> getAll();
}
