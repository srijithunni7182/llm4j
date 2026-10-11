package io.github.llm4j.agent.task;

import io.github.llm4j.agent.tool.EffectPolicy;
import java.util.Objects;

/** A {@link Task} built from a lambda by the {@link Task} factory methods. */
final class FunctionTask implements Task {

    private final String name;
    private final TaskEffect effect;
    private final EffectPolicy policy;
    private final TaskFunction body;
    private final boolean usesModel;

    FunctionTask(String name, TaskEffect effect, EffectPolicy policy, TaskFunction body) {
        this(name, effect, policy, body, false);
    }

    FunctionTask(String name, TaskEffect effect, EffectPolicy policy, TaskFunction body, boolean usesModel) {
        if (!Task.isValidName(name)) {
            throw new IllegalArgumentException("task name \"" + name + "\" must be a letter followed by letters, digits, _ or -");
        }
        this.name = name;
        this.effect = Objects.requireNonNull(effect, "effect");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.body = Objects.requireNonNull(body, "body");
        this.usesModel = usesModel;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public TaskResult run(TaskContext context) throws Exception {
        return body.run(context);
    }

    @Override
    public TaskEffect effect() {
        return effect;
    }

    @Override
    public boolean usesModel() {
        return usesModel;
    }

    @Override
    public EffectPolicy policy() {
        return policy;
    }

    @Override
    public String toString() {
        return "Task[" + name + ", " + effect + "]";
    }
}
