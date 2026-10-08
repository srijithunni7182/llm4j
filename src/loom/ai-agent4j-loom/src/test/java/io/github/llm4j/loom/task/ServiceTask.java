package io.github.llm4j.loom.task;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;

/** Listed in this module's test {@code META-INF/services}, so the {@code weave} CLI and every default executor find it. */
public class ServiceTask implements Task {

    @Override
    public String getName() {
        return "ServiceTask";
    }

    @Override
    public TaskEffect effect() {
        return TaskEffect.NONE;
    }

    @Override
    public TaskResult run(TaskContext context) {
        return TaskResult.value("from the service loader: " + context.arg("who"));
    }
}
