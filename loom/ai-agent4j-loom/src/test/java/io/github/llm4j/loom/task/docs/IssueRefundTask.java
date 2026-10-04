package io.github.llm4j.loom.task.docs;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A payment for the documentation's refund example: idempotent, with a fake provider that remembers the keys it has seen. */
public class IssueRefundTask implements Task {

    /** The "payment provider": one receipt per idempotency key. */
    public static final Map<String, String> PROVIDER = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "IssueRefund";
    }

    @Override
    public TaskEffect effect() {
        return TaskEffect.CHANGES;
    }

    @Override
    public EffectPolicy policy() {
        return new EffectPolicy(EffectPolicy.OnUnknown.SKIP, true, 0);
    }

    @Override
    public TaskResult run(TaskContext context) {
        context.requireArg("order", String.class);
        context.requireArg("amount", Double.class);
        String receipt = PROVIDER.computeIfAbsent(context.idempotencyKey(), k -> "rcpt-" + (PROVIDER.size() + 1));
        return TaskResult.value(receipt);
    }
}
