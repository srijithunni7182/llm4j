package io.github.llm4j.loom.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default thread-safe implementation of VariableContext.
 */
public class DefaultVariableContext implements VariableContext {
    private final Map<String, Object> variables = new ConcurrentHashMap<>();
    private final VariableContext parent;

    public DefaultVariableContext() {
        this.parent = null;
    }

    private DefaultVariableContext(VariableContext parent) {
        this.parent = parent;
    }

    @Override
    public void setVariable(String name, Object value) {
        if (value == null) value = "";
        variables.put(name, value);
    }

    @Override
    public Object getVariable(String name) {
        Object val = variables.get(name);
        if (val == null && parent != null) {
            return parent.getVariable(name);
        }
        return val != null ? val : "";
    }

    @Override
    public Map<String, Object> getAll() {
        Map<String, Object> all = parent != null ? parent.getAll() : new HashMap<>();
        all.putAll(variables);
        return all;
    }

    @Override
    public VariableContext pushFrame() {
        return new DefaultVariableContext(this);
    }

    @Override
    public VariableContext popFrame() {
        return this.parent;
    }
}
