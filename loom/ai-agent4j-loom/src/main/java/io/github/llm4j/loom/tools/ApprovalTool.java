package io.github.llm4j.loom.tools;

import io.github.llm4j.agent.Tool;
import java.util.Map;

/** Marks a tool as needing a person's approval before each call ({@code approve: [..]} in the script). */
public final class ApprovalTool implements Tool {

    private final Tool delegate;

    public ApprovalTool(Tool delegate) {
        this.delegate = delegate;
    }

    public Tool delegate() {
        return delegate;
    }

    @Override
    public String getName() {
        return delegate.getName();
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
        return true;
    }

    @Override
    public java.util.Map<String, Object> getParametersSchema() {
        return delegate.getParametersSchema();
    }
}
