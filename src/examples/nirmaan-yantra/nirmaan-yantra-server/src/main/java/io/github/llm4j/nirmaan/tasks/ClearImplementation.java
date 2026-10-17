package io.github.llm4j.nirmaan.tasks;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The "fresh start": removes the implementation (source files that are not tests) and keeps the tests, the build files and the
 * documents, so Vihaan can write the code again from the spec without being anchored to a failed attempt.
 */
public final class ClearImplementation implements Task {

    @Override public String getName() { return "ClearImplementation"; }

    @Override public String getDescription() { return "Deletes the implementation source files, keeping tests, build files and documents"; }

    @Override public TaskEffect effect() { return TaskEffect.CHANGES; }

    @Override public EffectPolicy policy() { return Tasks.REPEATABLE; }

    @Override
    public TaskResult run(TaskContext context) throws IOException {
        Path root = Sandbox.code(context.requireArg("project", String.class));
        int removed = 0;
        for (Path file : ProjectFiles.list(root)) {
            if (ProjectFiles.isSource(file) && !ProjectFiles.isTest(file) && !ProjectFiles.isBuildFile(file)) {
                Files.delete(root.resolve(file));
                removed++;
            }
        }
        return TaskResult.ok().with("removed", removed);
    }
}
