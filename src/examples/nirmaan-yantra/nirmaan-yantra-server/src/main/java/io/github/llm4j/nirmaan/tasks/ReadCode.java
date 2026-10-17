package io.github.llm4j.nirmaan.tasks;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shows the code to an agent: {@code run ReadCode(project = id, focus = failing_log) -> code_dump}, then {@code code_dump.value}.
 *
 * <p>With a {@code focus} (a build or test log) the agent sees only what that log is about: the files the log names, every test, and
 * the build files, so a long project does not drown the error. With no focus, or when the log names nothing, it sees the whole
 * project. The text is capped at {@value #MAX_CHARS} characters, and it says when files were left out.
 */
public final class ReadCode implements Task {

    static final int MAX_CHARS = 60_000;

    @Override public String getName() { return "ReadCode"; }

    @Override public String getDescription() { return "Reads the project's files for an agent, focused on a log when given one"; }

    @Override public TaskEffect effect() { return TaskEffect.READS; }

    @Override
    public TaskResult run(TaskContext context) throws IOException {
        Path root = Sandbox.code(context.requireArg("project", String.class));
        String focus = context.arg("focus", String.class) == null ? "" : context.arg("focus", String.class);
        List<Path> all = ProjectFiles.list(root).stream().filter(p -> ProjectFiles.readable(root.resolve(p))).toList();

        List<Path> chosen = all;
        if (!focus.isBlank()) {
            String log = focus.toLowerCase(Locale.ROOT);
            List<Path> mentioned = all.stream().filter(p -> log.contains(p.getFileName().toString().toLowerCase(Locale.ROOT))).toList();
            if (!mentioned.isEmpty()) {
                chosen = all.stream().filter(p -> mentioned.contains(p) || ProjectFiles.isTest(p) || ProjectFiles.isBuildFile(p)).toList();
            }
        }

        StringBuilder text = new StringBuilder();
        List<String> omitted = new ArrayList<>();
        for (Path file : chosen) {
            String block = "\n--- FILE: " + file.toString().replace('\\', '/') + " ---\n" + Files.readString(root.resolve(file)) + "\n";
            if (text.length() + block.length() > MAX_CHARS) omitted.add(file.toString());
            else text.append(block);
        }
        if (chosen.size() < all.size()) text.append("\n[").append(all.size() - chosen.size()).append(" other files not shown: they are not named in the log]\n");
        if (!omitted.isEmpty()) text.append("\n[too long to show: ").append(String.join(", ", omitted)).append("]\n");
        if (text.length() == 0) text.append("(the project has no files yet)");
        return TaskResult.value(text.toString()).with("files", chosen.size());
    }
}
