package web;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Saves an approved article to output/<title>.md and returns the path. A task, not a prompt: saving is something that must happen exactly when the
 * script says so (after the person said yes), so it is code. It changes the disk, so a simulated run never runs it.
 */
public final class SaveMarkdown implements Task {

    @Override public String getName() { return "SaveMarkdown"; }

    @Override public String getDescription() { return "Saves an article as a markdown file under output/ and returns its path."; }

    @Override
    public TaskResult run(TaskContext context) throws Exception {
        String title = String.valueOf(context.requireArg("title"));
        String content = String.valueOf(context.requireArg("content"));
        Path dir = Path.of(System.getProperty("web.output", "output"));
        Files.createDirectories(dir);
        Path file = dir.resolve(slug(title) + ".md");
        Files.writeString(file, content);
        return TaskResult.value(file.toString());
    }

    /** A file name from a title: lower case, letters and digits, dashes between. */
    static String slug(String title) {
        String s = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return s.isEmpty() ? "article" : s.substring(0, Math.min(s.length(), 60));
    }
}
