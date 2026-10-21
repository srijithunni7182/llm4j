package router;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;

/** Looks an order up. A stand-in for a call to the shop's system. */
public class LookupOrder implements Task {
    @Override public String getName() { return "LookupOrder"; }
    @Override public TaskEffect effect() { return TaskEffect.NONE; }
    @Override public TaskResult run(TaskContext context) {
        String order = String.valueOf(context.arg("order"));
        return TaskResult.outcome("ok").withValue("order " + order + " shipped, arrives Thursday");
    }
}
