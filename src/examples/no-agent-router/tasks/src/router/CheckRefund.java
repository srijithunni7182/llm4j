package router;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;

/** The refund policy, in code: exact, free and testable. The classifier only says that the customer asks for a refund. */
public class CheckRefund implements Task {
    @Override public String getName() { return "CheckRefund"; }
    @Override public TaskEffect effect() { return TaskEffect.NONE; }
    @Override public TaskResult run(TaskContext context) {
        int amount = Integer.parseInt(String.valueOf(context.arg("amount")));
        return amount <= 100
                ? TaskResult.outcome("approved")
                : TaskResult.outcome("needs_review").reason("over the 100 limit");
    }
}
