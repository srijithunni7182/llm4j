package io.github.llm4j.loom.runtime;

import java.util.Map;

/**
 * A read view that sees a few block-local names (a {@code for each} item, {@code _error}) on top of
 * the workflow's variables. Writes go straight through to the workflow, so nothing is lost.
 */
public final class ScopedContext implements VariableContext {

    private final Map<String, Object> locals;
    private final VariableContext base;

    public ScopedContext(Map<String, Object> locals, VariableContext base) {
        this.locals = locals;
        this.base = base;
    }

    @Override
    public void setVariable(String name, Object value) {
        base.setVariable(name, value);
    }

    @Override
    public Object getVariable(String name) {
        return locals.containsKey(name) ? locals.get(name) : base.getVariable(name);
    }

    @Override
    public Map<String, Object> getAll() {
        Map<String, Object> all = base.getAll();
        all.putAll(locals);
        return all;
    }

    @Override
    public VariableContext pushFrame() {
        return base.pushFrame();
    }

    @Override
    public VariableContext popFrame() {
        return base.popFrame();
    }
}
