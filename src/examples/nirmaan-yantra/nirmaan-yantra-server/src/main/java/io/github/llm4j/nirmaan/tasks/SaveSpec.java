package io.github.llm4j.nirmaan.tasks;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

/** Stores Rishi's structured spec and writes {@code SPEC.md} from it: {@code run SaveSpec(project = id, spec = spec_out) -> spec_saved}. */
public final class SaveSpec implements Task {

    @Override public String getName() { return "SaveSpec"; }

    @Override public String getDescription() { return "Stores the technical spec and renders SPEC.md"; }

    @Override public TaskEffect effect() { return TaskEffect.CHANGES; }

    @Override public EffectPolicy policy() { return Tasks.REPEATABLE; }

    @Override
    @SuppressWarnings("unchecked")
    public TaskResult run(TaskContext context) throws IOException {
        String project = context.requireArg("project", String.class);
        if (!(context.arg("spec") instanceof Map<?, ?> given)) return TaskResult.rejected("the spec is not a set of fields");
        Map<String, Object> spec = (Map<String, Object>) given;
        String problem = SpecStore.problem(spec);
        if (problem != null) return TaskResult.rejected(problem);
        SpecStore.save(Sandbox.meta(project), spec);
        Files.writeString(Sandbox.code(project).resolve("SPEC.md"), SpecStore.render(spec));
        return TaskResult.value("SPEC.md");
    }
}
