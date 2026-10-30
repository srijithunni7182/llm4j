package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A case's {@code real:} list: tasks that run their own code under test instead of a scripted
 * result. Only a task that declares {@link TaskEffect#NONE} may, so a test never sends, pays or
 * writes by accident; one that does not say counts as having effects.
 */
final class RealTasks {

    private RealTasks() {}

    /** The names a case lists, empty when it lists none. */
    static Set<String> names(JsonNode real) {
        Set<String> out = new LinkedHashSet<>();
        if (real != null && real.isArray()) real.forEach(n -> out.add(n.asText()));
        return out;
    }

    /** Why {@code name} cannot be real in this case, or null when it can. */
    static String problem(String name, Set<String> run, JsonNode scripted, TaskRegistry registry) {
        if (!run.contains(name)) return "'" + name + "' is not a task run by the script";
        if (scripted != null && scripted.has(name))
            return "'" + name + "' is both real and scripted; keep one of them";
        if (registry.get(name).effect() != TaskEffect.NONE)
            return "'"
                    + name
                    + "' has effects; a test cannot run it for real; script its result in tasks:";
        return null;
    }

    /**
     * The task as the run sees it: its own code, counted as a call so {@code expect.calls} holds.
     */
    static Task recorded(Task real, Recorder recorder) {
        return new Task() {
            @Override
            public String getName() {
                return real.getName();
            }

            @Override
            public TaskEffect effect() {
                return real.effect();
            }

            @Override
            public java.util.Set<String> okOutcomes() {
                return real.okOutcomes();
            }

            @Override
            public String getDescription() {
                return real.getDescription();
            }

            @Override
            public boolean usesModel() {
                return real.usesModel();
            }

            @Override
            public TaskResult run(TaskContext context) throws Exception {
                recorder.call("task:" + real.getName());
                return real.run(context);
            }
        };
    }
}
