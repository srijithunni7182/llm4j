package io.github.llm4j.loom.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskResult;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The validating-task example in chapter 6 is not only compiled: its table of cases is run against it. */
class ValidatingTaskExampleTest {

    @TempDir Path dir;

    private Task example() throws Exception {
        String chapter = Files.readString(Path.of("..", "..", "..", "docs", "guide", "06-build-the-workflow.md"));
        Matcher m = Pattern.compile("<!-- compiles -->\\s*```java\\n(package safety;.*?)```", Pattern.DOTALL).matcher(chapter);
        assertThat(m.find()).isTrue();
        Path file = dir.resolve("src/safety/CheckScript.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, m.group(1));
        Path out = Files.createDirectories(dir.resolve("out"));
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-cp", System.getProperty("java.class.path"), "-d", out.toString(), file.toString())).isZero();
        URLClassLoader loader = new URLClassLoader(new URL[] {out.toUri().toURL()}, getClass().getClassLoader());
        return (Task) loader.loadClass("safety.CheckScript").getDeclaredConstructor().newInstance();
    }

    private String outcome(Task task, String script) throws Exception {
        return task.run(TaskContext.of(Map.of("script", script), Map.of(), "test", "")).outcome();
    }

    @Test
    void theTableInTheChapterIsWhatTheCodeDoes() throws Exception {
        Task task = example();
        assertThat(outcome(task, "df -h\nuptime\n# a comment")).isEqualTo("ok");
        assertThat(outcome(task, "ls /var/log | head")).isEqualTo("needs_review");
        assertThat(outcome(task, "rm -rf /tmp/x")).isEqualTo("blocked");
        assertThat(outcome(task, "nice -n 5 rm -rf *")).isEqualTo("needs_review");
        assertThat(outcome(task, "sudo systemctl restart x")).isEqualTo("blocked");
        assertThat(outcome(task, "cat /etc/hosts; rm x")).isEqualTo("needs_review");
        assertThat(outcome(task, "df -h\nrm x")).as("one forbidden line blocks the whole script").isEqualTo("blocked");
    }
}
