package io.github.llm4j.agent.task;

/** Listed in {@code META-INF/services} of the test resources, so {@link TaskRegistry#discovered()} finds it. */
public class DiscoveredTestTask implements Task {

    @Override
    public String getName() {
        return "DiscoveredTask";
    }

    @Override
    public TaskResult run(TaskContext context) {
        return TaskResult.value("found");
    }

    @Override
    public TaskEffect effect() {
        return TaskEffect.NONE;
    }
}
