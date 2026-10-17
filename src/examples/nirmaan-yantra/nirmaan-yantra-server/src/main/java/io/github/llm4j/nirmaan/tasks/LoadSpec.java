package io.github.llm4j.nirmaan.tasks;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;
import java.io.IOException;
import java.util.Map;

/**
 * Reads the stored spec back: {@code run LoadSpec(project = id) -> spec_doc}, then {@code spec_doc.test_command}, {@code spec_doc.markdown}, ...
 * Every phase starts here, so a phase always works from the latest spec (Rishi may have changed it after QA).
 */
public final class LoadSpec implements Task {

    @Override public String getName() { return "LoadSpec"; }

    @Override public String getDescription() { return "Reads the stored technical spec"; }

    @Override public TaskEffect effect() { return TaskEffect.READS; }

    @Override
    public TaskResult run(TaskContext context) throws IOException {
        Map<String, Object> spec = SpecStore.load(Sandbox.meta(context.requireArg("project", String.class)));
        if (spec == null) {
            // The same fields, empty, so a script that reads them still has something to read (a free `weave eval --mock` run never saves a spec).
            return TaskResult.outcome("missing").reason("no spec has been saved for this project yet")
                    .withAll(SpecStore.view(java.util.Map.of())).with("markdown", "(no spec has been saved yet)");
        }
        return TaskResult.ok(SpecStore.view(spec));
    }
}
