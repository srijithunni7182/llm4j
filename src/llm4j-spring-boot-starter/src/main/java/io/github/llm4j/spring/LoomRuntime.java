package io.github.llm4j.spring;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.LoomRun;
import io.github.llm4j.loom.execution.ToolRegistry;
import java.util.List;
import java.util.Map;

/**
 * Runs the workflows of one parsed script. A {@link HarnessExecutor} belongs to one run (its variables, journal and budgets), so this
 * holds the shared parts, the script, tools and models, and makes a new executor for each run. Nothing here is Spring-specific: the same
 * three lines work in a plain {@code main}.
 */
public final class LoomRuntime {

    private final LoomScript script;
    private final ToolRegistry tools;
    private final LLMClientFactory models;
    private final Map<String, String> toolReach;
    private final List<LoomExecutorCustomizer> customizers;

    public LoomRuntime(LoomScript script, ToolRegistry tools, LLMClientFactory models, Map<String, String> toolReach,
                       List<LoomExecutorCustomizer> customizers) {
        this.script = script;
        this.tools = tools;
        this.models = models;
        this.toolReach = toolReach == null ? Map.of() : toolReach;
        this.customizers = customizers == null ? List.of() : List.copyOf(customizers);
    }

    public LoomScript script() { return script; }

    /** A new, initialized executor for one run, so a script that is invalid or missing a tool or key fails here, with every problem listed. */
    public HarnessExecutor newExecutor() {
        HarnessExecutor executor = new HarnessExecutor(script, tools, models);
        if (!toolReach.isEmpty()) executor.setToolReach(toolReach);
        customizers.forEach(c -> c.customize(executor));
        executor.initialize();
        return executor;
    }

    /** Runs a workflow to the end on the calling thread. */
    public void run(String workflow, Map<String, String> inputs) {
        HarnessExecutor executor = newExecutor();
        try {
            executor.executeWorkflow(workflow, inputs == null ? Map.of() : inputs);
        } finally {
            executor.shutdown();
        }
    }

    /** Starts a workflow on its own thread and returns at once; the handle can be awaited, steered, paused or cancelled. */
    public LoomRun start(String workflow, Map<String, String> inputs) {
        return LoomRun.start(newExecutor(), workflow, inputs == null ? Map.of() : inputs);
    }
}
