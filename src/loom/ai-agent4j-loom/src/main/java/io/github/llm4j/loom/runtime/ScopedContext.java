package io.github.llm4j.loom.runtime;

import java.util.Map;

/**
 * A read view that sees a few block-local names (a {@code for each} item, {@code _error}) on top of
 * the workflow's variables. Writes go straight through to the workflow, so nothing is lost.
 */
public final class ScopedContext implements VariableContext {

    private final Map<String, Object> locals;
    private final VariableContext base;

    /**
     * Where a block-local name's origin is kept inside the locals map, so it travels with the
     * locals into parallel branches. These entries are never shown as variables.
     */
    public static final String ORIGIN_PREFIX = "\u0001origin:";

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
    public Object originOf(String name) {
        String head = name.indexOf('.') > 0 ? name.substring(0, name.indexOf('.')) : name;
        if (locals.containsKey(head)) return locals.get(ORIGIN_PREFIX + head);
        return base.originOf(name);
    }

    /** Records, in a locals map, where a block-local name (a for each item) came from. */
    public static void putLocalOrigin(Map<String, Object> locals, String name, Object origin) {
        locals.put(ORIGIN_PREFIX + name, origin);
    }

    @Override
    public void setOrigin(String name, Object origin) {
        base.setOrigin(name, origin);
    }

    @Override
    public void clearOrigin(String name) {
        base.clearOrigin(name);
    }

    @Override
    public Map<String, Object> getAll() {
        Map<String, Object> all = base.getAll();
        locals.forEach(
                (k, v) -> {
                    if (!k.startsWith(ORIGIN_PREFIX)) all.put(k, v);
                });
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
