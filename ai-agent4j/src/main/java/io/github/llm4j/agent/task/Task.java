package io.github.llm4j.agent.task;

import io.github.llm4j.agent.tool.EffectPolicy;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A deterministic step of a workflow: plain code, no model. A Loom script runs it with {@code run Name(arg = value) -> result}.
 *
 * <p>Some parts of a workflow are too important to leave to a model: checking a refund against policy, calling a payments API,
 * writing an audit record. A task is how those parts are written. It is journaled and replayed like any other step, costs no tokens,
 * and is never offered to a model as a tool.
 *
 * <p>Implementations must be thread-safe: a workflow may run the same task from several parallel branches at once.
 */
public interface Task {

    /** What a task name looks like: a letter, then letters, digits, {@code _} or {@code -}. Loom scripts write it as an identifier. */
    Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");

    /** The name a script uses: {@code run RefundPolicy(...)}. */
    String getName();

    /** What the task does, for listings and error messages. */
    default String getDescription() {
        return "";
    }

    /**
     * Does the work. Read inputs from {@code context}; return a {@link TaskResult}. Throw {@link TaskNotPerformed} when nothing was
     * done and the step can safely be tried again.
     */
    TaskResult run(TaskContext context) throws Exception;

    /**
     * What this task does to the outside world. The default is {@link TaskEffect#CHANGES}, the safe assumption: such a task is not run
     * in a simulation and is never repeated when its earlier outcome is unknown. A pure task overrides this to {@link TaskEffect#NONE}.
     */
    default TaskEffect effect() {
        return TaskEffect.CHANGES;
    }

    /** How a task that changes things is treated when an earlier attempt's outcome is unknown. Ignored for other effects. */
    default EffectPolicy policy() {
        return EffectPolicy.DEFAULT;
    }

    /** Whether a person must approve this call before it runs. Mirrors {@code Tool.requiresApproval}. */
    default boolean requiresApproval(Map<String, Object> args) {
        return false;
    }

    /** Whether {@code name} is a legal task name. */
    static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** A task that only computes: {@link TaskEffect#NONE}. */
    static Task pure(String name, TaskFunction body) {
        return new FunctionTask(name, TaskEffect.NONE, EffectPolicy.DEFAULT, body);
    }

    /** A task that only observes the outside world: {@link TaskEffect#READS}. */
    static Task reads(String name, TaskFunction body) {
        return new FunctionTask(name, TaskEffect.READS, EffectPolicy.DEFAULT, body);
    }

    /** A task that changes the outside world: {@link TaskEffect#CHANGES}, under the given policy. */
    static Task changes(String name, EffectPolicy policy, TaskFunction body) {
        return new FunctionTask(name, TaskEffect.CHANGES, Objects.requireNonNull(policy, "policy"), body);
    }

    /** A task with an explicit effect and policy. */
    static Task of(String name, TaskEffect effect, EffectPolicy policy, TaskFunction body) {
        return new FunctionTask(name, effect, policy, body);
    }
}
