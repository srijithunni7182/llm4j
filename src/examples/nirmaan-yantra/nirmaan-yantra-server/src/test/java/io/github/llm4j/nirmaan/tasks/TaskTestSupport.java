package io.github.llm4j.nirmaan.tasks;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Runs a task on its own, with no model and no workflow, in a project whose folder is a fresh temporary one. */
final class TaskTestSupport {

    private TaskTestSupport() {}

    /** Points the factory's workspace at a new folder and returns it, so each test starts with an empty one. */
    static Path newWorkspace(Path folder) {
        System.setProperty("nirmaan.workspace", folder.toString());
        return folder;
    }

    static TaskResult run(Task task, Map<String, Object> args) throws Exception {
        return task.run(TaskContext.of(args, Map.of(), "test", "test-key"));
    }

    static Path write(Path code, String relative, String content) throws Exception {
        Path file = code.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
