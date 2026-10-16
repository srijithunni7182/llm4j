package io.github.llm4j.nirmaan.tasks;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import java.io.IOException;
import java.nio.file.Path;

/** Zips the finished project to {@code <workspace>/<id>/<id>_FINAL.zip} (build output and tooling folders left out). */
public final class PackageProject implements Task {

    @Override public String getName() { return "PackageProject"; }

    @Override public String getDescription() { return "Zips the finished project"; }

    @Override public TaskEffect effect() { return TaskEffect.CHANGES; }

    @Override public EffectPolicy policy() { return Tasks.REPEATABLE; }

    @Override
    public TaskResult run(TaskContext context) throws IOException {
        String project = context.requireArg("project", String.class);
        Path zip = ProjectArtifacts.archivePath(project);
        int count = ProjectArtifacts.zip(project, zip);
        return TaskResult.value(zip.getFileName().toString()).with("files", count);
    }
}
