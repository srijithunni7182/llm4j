package router;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;

/** Opens a ticket in a queue. A stand-in for the ticketing system. */
public class OpenTicket implements Task {
    @Override public String getName() { return "OpenTicket"; }
    @Override public TaskEffect effect() { return TaskEffect.NONE; }
    @Override public TaskResult run(TaskContext context) {
        return TaskResult.outcome("ok").withValue("ticket in " + context.arg("queue"));
    }
}
