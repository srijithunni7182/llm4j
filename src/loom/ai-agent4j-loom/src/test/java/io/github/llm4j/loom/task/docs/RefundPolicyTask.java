package io.github.llm4j.loom.task.docs;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;

/** The rule from the documentation's refund example, so the documented scripts load and run against real tasks. */
public class RefundPolicyTask implements Task {

    static final double LIMIT = 50;

    @Override
    public String getName() {
        return "RefundPolicy";
    }

    @Override
    public TaskEffect effect() {
        return TaskEffect.NONE;
    }

    @Override
    public TaskResult run(TaskContext context) {
        String order = context.requireArg("order", String.class);
        double amount = context.requireArg("amount", Double.class);
        if (amount > LIMIT) return TaskResult.rejected("amount " + (long) amount + " is over the " + (long) LIMIT + " limit");
        return TaskResult.outcome("approved").with("order", order);
    }
}
