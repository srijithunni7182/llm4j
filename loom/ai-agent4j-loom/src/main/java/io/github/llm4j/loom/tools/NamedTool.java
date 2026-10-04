package io.github.llm4j.loom.tools;

import io.github.llm4j.agent.Tool;
import java.util.Map;

/** Presents a tool under the name the script gave it, so prompts and approvals use the script's names. */
public final class NamedTool implements Tool {

    private final String name;
    private final Tool delegate;

    public NamedTool(String name, Tool delegate) {
        this.name = name;
        this.delegate = delegate;
    }

    public Tool delegate() {
        return delegate;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        return delegate.execute(args);
    }

    @Override
    public boolean requiresApproval(Map<String, Object> args) {
        return delegate.requiresApproval(args);
    }

    @Override
    public java.util.Map<String, Object> getParametersSchema() {
        return delegate.getParametersSchema();
    }
}
