package io.github.llm4j.tools;

import io.github.llm4j.agent.Tool;
import java.util.Map;

/** Adds the script author's {@code description:} to what the model is told about a tool. */
public final class DescribedTool implements Tool {

    private final Tool delegate;
    private final String extra;

    public DescribedTool(Tool delegate, String extra) {
        this.delegate = delegate;
        this.extra = extra;
    }

    /** The tool this one adds a description to. */
    public Tool delegate() {
        return delegate;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription() + "\n" + extra;
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
