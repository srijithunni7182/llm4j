package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;

/**
 * Checks for {@code run} statements: the task exists, it has a model exactly when it uses one, and
 * a retry can't repeat an effect.
 */
final class TaskChecks {

    private TaskChecks() {}

    static void run(ScriptValidator.Checker c) {
        for (WorkflowDef w : c.script().getWorkflows()) {
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (!(s instanceof RunStmt r)) return;
                        String construct = "run " + r.getTaskName();
                        Task task = c.context().tasks().get(r.getTaskName());
                        if (task == null) {
                            c.error(
                                    r.getLine(),
                                    construct,
                                    "unknown task; register it with TaskRegistry.register(...) or list its class in "
                                            + "META-INF/services/io.github.llm4j.agent.task.Task"
                                            + (c.context().tasks().isEmpty()
                                                    ? " (no tasks are registered)"
                                                    : " (known tasks: "
                                                            + String.join(
                                                                    ", ",
                                                                    c.context().tasks().names())
                                                            + ")")
                                            + ". weave sees only the classes on its class path: for a task you wrote in Java, build the project (mvn compile; weave then looks in target/classes) or pass --classes <dir>");
                            return;
                        }
                        if (task.usesModel() && r.getModel() == null) {
                            c.error(
                                    r.getLine(),
                                    construct,
                                    r.getTaskName()
                                            + " reasons with a model, so the step must name one: run "
                                            + r.getTaskName()
                                            + "(...) model \"gemini-2.5-flash\" -> result");
                        }
                        if (!task.usesModel() && r.getModel() != null) {
                            c.error(
                                    r.getLine(),
                                    construct,
                                    r.getTaskName()
                                            + " is plain code and never calls a model, so model \""
                                            + r.getModel()
                                            + "\" would be ignored. Remove it, or make the task return true from usesModel()");
                        }
                        boolean repeatable =
                                task.effect() != TaskEffect.CHANGES
                                        || task.policy().idempotent()
                                        || task.policy().onUnknown()
                                                == EffectPolicy.OnUnknown.RETRY;
                        if (r.getRetryCount() > 0 && !repeatable) {
                            c.error(
                                    r.getLine(),
                                    construct,
                                    "retry "
                                            + r.getRetryCount()
                                            + " could repeat an effect: "
                                            + r.getTaskName()
                                            + " changes things and is not idempotent. Make the task idempotent (EffectPolicy), or remove retry and handle failure with on_failure");
                        }
                    });
        }
    }
}
