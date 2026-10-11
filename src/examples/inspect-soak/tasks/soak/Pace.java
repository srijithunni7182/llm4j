package soak;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;

/**
 * Waits for a number of seconds, so the soak workflow lasts as long as a real long-running one. It is a task,
 * not an agent: no model, no tokens, and it shows up in the console as a deterministic step.
 *
 * <p>It is journaled like any other step, so a run that is resumed after a pause does not wait again for
 * the pauses it has already done.
 */
public final class Pace implements Task {

    @Override
    public String getName() {
        return "Pace";
    }

    @Override
    public String getDescription() {
        return "Waits for the given number of seconds (soak test only).";
    }

    @Override
    public TaskEffect effect() {
        return TaskEffect.NONE;
    }

    @Override
    public TaskResult run(TaskContext context) throws Exception {
        long seconds = context.requireArg("seconds", Double.class).longValue();
        for (long i = 0; i < seconds; i++) {
            Thread.sleep(1000);
        }
        return TaskResult.value("waited " + seconds + "s");
    }
}
