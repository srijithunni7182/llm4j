package io.github.llm4j.agent.task;

import io.github.llm4j.agent.tool.EffectPolicy;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A step of a workflow written as code: deterministic by default, and with a model only when {@link #usesModel()} says so. A Loom script runs it with {@code run Name(arg = value) -> result}.
 *
 * <p>Some parts of a workflow are too important to leave to a model: checking a refund against policy, calling a payments API,
 * writing an audit record. A task is how those parts are written. It is journaled and replayed like any other step, costs no tokens
 * (unless it uses a model, see {@link #usesModel()}), and is never offered to a model as a tool.
 *
 * <p>Implementations must be thread-safe: a workflow may run the same task from several parallel branches at once.
 */
public interface Task {

    /** What a task name looks like: a letter, then letters, digits, {@code _} or {@code -}. Loom scripts write it as an identifier. */
    Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");

    /** The name a script uses: {@code run RefundPolicy(...)}. */
    String getName();

    /**
     * The outcomes that mean "it did what was wanted". A workflow asks {@code x is ok} of a step's result and gets true exactly when the
     * outcome is one of these (or the step was only described in a simulated run). Anything else, an unexpected word included, is not ok.
     * The default is {@code ok}, which is what {@link TaskResult#ok()} and {@link TaskResult#value(Object)} produce; a task that reports
     * {@code passed} and {@code failed} says {@code Set.of("passed")}.
     */
    default java.util.Set<String> okOutcomes() {
        return java.util.Set.of("ok");
    }

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

    /**
     * Whether this task calls a model through {@link TaskContext#llm()}. A script then has to give the step one ({@code model "..."}) and a
     * step that gives one to a task that does not use it is refused, so what a script says about where models run stays true. The default is
     * false: a task is plain code. A task that returns {@code true} should also report an effect of {@link TaskEffect#READS} (or
     * {@link TaskEffect#CHANGES}), not {@link TaskEffect#NONE}: a model's answer is not a pure function of its input.
     */
    default boolean usesModel() {
        return false;
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

    /**
     * A task that reasons with a model, such as classifying an intent: {@link TaskEffect#READS}, and it calls {@link TaskContext#llm()}.
     * The script names the model: {@code run Classify(text = msg) model "gemini-2.5-flash" -> intent}.
     */
    static Task withModel(String name, TaskFunction body) {
        return new FunctionTask(name, TaskEffect.READS, EffectPolicy.DEFAULT, body, true);
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
