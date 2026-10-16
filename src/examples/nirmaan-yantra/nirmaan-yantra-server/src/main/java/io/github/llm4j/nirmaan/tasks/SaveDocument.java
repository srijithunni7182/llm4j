package io.github.llm4j.nirmaan.tasks;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes one document the workflow composed (the PRD, a review, the QA report): {@code run SaveDocument(project = id, path = "PRD.md", content = text)}. */
public final class SaveDocument implements Task {

    @Override public String getName() { return "SaveDocument"; }

    @Override public String getDescription() { return "Writes one document into the project folder"; }

    @Override public TaskEffect effect() { return TaskEffect.CHANGES; }

    @Override public EffectPolicy policy() { return Tasks.REPEATABLE; }

    @Override
    public TaskResult run(TaskContext context) throws IOException {
        Path root = Sandbox.code(context.requireArg("project", String.class));
        String content = String.valueOf(context.arg("content") == null ? "" : context.arg("content"));
        if (content.isBlank()) return TaskResult.rejected("the document is empty");
        if (content.getBytes(StandardCharsets.UTF_8).length > SaveFiles.MAX_BYTES) return TaskResult.rejected("the document is over 1 MB");
        Path target;
        try {
            target = Sandbox.resolve(root, context.requireArg("path", String.class));
        } catch (io.github.llm4j.agent.task.TaskNotPerformed refused) {
            return TaskResult.rejected(refused.getMessage());
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, SaveFiles.unfence(content), StandardCharsets.UTF_8);
        return TaskResult.value(root.relativize(target).toString());
    }
}
