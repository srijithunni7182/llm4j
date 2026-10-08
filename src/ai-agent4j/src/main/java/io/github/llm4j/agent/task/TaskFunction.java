package io.github.llm4j.agent.task;

/** The body of a task, for the {@link Task} factory methods. */
@FunctionalInterface
public interface TaskFunction {

    TaskResult run(TaskContext context) throws Exception;
}
