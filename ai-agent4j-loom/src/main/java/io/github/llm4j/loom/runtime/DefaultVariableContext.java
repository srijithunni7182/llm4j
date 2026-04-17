package io.github.llm4j.loom.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default thread-safe implementation of VariableContext.
 */
public class DefaultVariableContext implements VariableContext {
    private final Map<String, String> variables = new ConcurrentHashMap<>();

    @Override
    public void setVariable(String name, String value) {
        if (value == null) value = "";
        variables.put(name, value);
    }

    @Override
    public String getVariable(String name) {
        return variables.getOrDefault(name, "");
    }

    @Override
    public Map<String, String> getAll() {
        return new HashMap<>(variables);
    }
}
