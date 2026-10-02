package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.runtime.RunJournal;
import java.util.Map;

/**
 * A tool whose class says nothing about whether it changes the world (one the host registered, an OpenAPI tool, an MCP tool) leaves a
 * mark in the journal each time it is called, in a script that can go back. A rewind then knows it may be crossing something it cannot
 * undo or classify, and asks first. Scripts that never rewind get no mark.
 */
final class RecordingTool implements Tool {

    /** The journal key part that marks a call of a tool that is not known to be harmless. */
    static final String MARK = "#unclassified:";

    private final Tool real;
    private final HarnessExecutor run;

    RecordingTool(Tool real, HarnessExecutor run) {
        this.real = real;
        this.run = run;
    }

    Tool real() {
        return real;
    }

    @Override
    public String getName() {
        return real.getName();
    }

    @Override
    public String getDescription() {
        return real.getDescription();
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String key = run.identityStep() + MARK + real.getName();
        RunJournal journal = run.journal();
        if (journal.get(key).isEmpty()) journal.put(key, new RunJournal.Entry("unclassified_call", "called"));
        return real.execute(args);
    }

    @Override
    public boolean requiresApproval(Map<String, Object> args) {
        return real.requiresApproval(args);
    }
}
