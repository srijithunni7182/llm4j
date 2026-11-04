package io.github.llm4j.loom.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Default thread-safe implementation of VariableContext. */
public class DefaultVariableContext implements VariableContext {
    private final Map<String, Object> variables = new ConcurrentHashMap<>();
    private final Map<String, Object> origins = new ConcurrentHashMap<>();
    private final VariableContext parent;
    private volatile java.util.function.BiConsumer<String, Object> observer;

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
        java.util.function.BiConsumer<String, Object> watch = observer;
        if (watch != null) watch.accept(name, value);
    }

    @Override
    public Object originOf(String name) {
        Object found = origins.get(name);
        if (found != null) return found;
        // A path with nothing recorded of its own is judged by the variable it is in.
        if (variables.containsKey(name)) return null;
        int dot = name.indexOf('.');
        if (dot > 0 && variables.containsKey(name.substring(0, dot))) return origins.get(name.substring(0, dot));
        return parent != null ? parent.originOf(name) : null;
    }

    @Override
    public void setOrigin(String name, Object origin) {
        // Setting a variable as a whole replaces whatever was recorded for its parts.
        if (name.indexOf('.') < 0) clearOrigin(name);
        origins.put(name, origin);
    }

    @Override
    public void clearOrigin(String name) {
        origins.remove(name);
        String prefix = name + ".";
        origins.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Tells {@code observer} each time a variable is set; null stops. Free when nobody watches. */
    public void setObserver(java.util.function.BiConsumer<String, Object> observer) {
        this.observer = observer;
    }

    @Override
    public void removeVariable(String name) {
        variables.remove(name);
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
