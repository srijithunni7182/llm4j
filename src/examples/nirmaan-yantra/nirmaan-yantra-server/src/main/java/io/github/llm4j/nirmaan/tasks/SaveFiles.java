package io.github.llm4j.nirmaan.tasks;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskNotPerformed;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes the files an agent produced into the project: {@code run SaveFiles(project = id, files = out.files)}.
 *
 * <p>The agent gives a list of {@code {path, content}}. Nothing is written unless every file is acceptable (a path inside the project,
 * at most {@value #MAX_FILES} files, each under 1 MB, and the test rules below), so a single bad file does not leave half a change behind.
 *
 * <p><b>Tests are locked.</b> A test that can be weakened is a test that proves nothing. With {@code may_change = "none"} (the default) no
 * existing test file may change and nothing may be written under {@code e2e-tests/}; new unit tests may still be added. With
 * {@code may_change = "e2e"} (the QA engineer's step) the end to end suite may be written and changed, and existing unit tests stay locked.
 */
public final class SaveFiles implements Task {

    static final int MAX_FILES = 200;
    static final int MAX_BYTES = 1_000_000;
    private static final Set<String> SCOPES = Set.of("none", "e2e");

    @Override public String getName() { return "SaveFiles"; }

    @Override public String getDescription() { return "Writes agent-produced files into the project folder, keeping tests locked unless told otherwise"; }

    @Override public TaskEffect effect() { return TaskEffect.CHANGES; }

    @Override public EffectPolicy policy() { return Tasks.REPEATABLE; }

    @Override
    public TaskResult run(TaskContext context) throws IOException {
        String project = context.requireArg("project", String.class);
        Path root = Sandbox.code(project);
        String scope = context.arg("may_change", String.class) == null ? "none" : context.arg("may_change", String.class).strip();
        if (!SCOPES.contains(scope)) return TaskResult.rejected("may_change must be none or e2e, not \"" + scope + "\"");
        if (!(context.arg("files") instanceof List<?> files) || files.isEmpty()) {
            return TaskResult.rejected("the agent gave no files");
        }
        if (files.size() > MAX_FILES) return TaskResult.rejected("the agent gave " + files.size() + " files; the limit is " + MAX_FILES);

        List<Path> targets = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        List<String> locked = new ArrayList<>();
        for (Object entry : files) {
            if (!(entry instanceof Map<?, ?> file)) return TaskResult.rejected("a file entry is not {path, content}");
            String relative = String.valueOf(file.get("path"));
            String text = file.get("content") == null ? "" : unfence(String.valueOf(file.get("content")));
            if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return TaskResult.rejected("\"" + relative + "\" is over 1 MB");
            Path target;
            try {
                target = Sandbox.resolve(root, relative);
            } catch (TaskNotPerformed refused) {
                return TaskResult.rejected(refused.getMessage());
            }
            Path inside = root.relativize(target);
            boolean exists = Files.isRegularFile(target);
            boolean differs = !exists || !Files.readString(target, StandardCharsets.UTF_8).strip().equals(text.strip());
            if (ProjectFiles.isE2e(inside)) {
                if (!scope.equals("e2e")) locked.add(inside + " (the end to end suite belongs to the QA engineer)");
            } else if (ProjectFiles.isTest(inside) && exists && differs) {
                locked.add(inside + " (an existing test)");
            }
            targets.add(target);
            contents.add(text);
        }
        if (!locked.isEmpty()) {
            return TaskResult.rejected("nothing was written. These files are locked: " + String.join(", ", locked)
                    + ". Fix the code instead of the test.");
        }

        List<String> written = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            Path target = targets.get(i);
            Path parent = Files.createDirectories(target.getParent());
            if (!parent.toRealPath().startsWith(root)) return TaskResult.rejected("\"" + root.relativize(target) + "\" goes through a link that leaves the project");
            Files.writeString(target, contents.get(i), StandardCharsets.UTF_8);
            if (target.getFileName().toString().endsWith(".sh")) target.toFile().setExecutable(true);
            written.add(root.relativize(target).toString());
        }
        return TaskResult.value(written).with("count", written.size());
    }

    /** Models sometimes wrap a whole file in a markdown fence; the fence is not part of the file. */
    static String unfence(String text) {
        String t = text.strip();
        if (t.startsWith("```") && t.endsWith("```") && t.length() >= 6) {
            int firstLine = t.indexOf('\n');
            if (firstLine > 0) return t.substring(firstLine + 1, t.length() - 3).stripTrailing() + "\n";
        }
        return text;
    }
}
