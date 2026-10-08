package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** A free (mock) evaluation must not run a task that changes the world: it is described, and the file the task would write is not there. */
class MockRunsDescribeChangesTest {

    @TempDir Path dir;

    private final ClassLoader before = Thread.currentThread().getContextClassLoader();

    @AfterEach
    void restore() {
        Thread.currentThread().setContextClassLoader(before);
    }

    private Path project(String effect) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Path script = dir.resolve("src/main/resources/main.loom");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "workflow Main(text) {\n    run WriteMarker(content = text) -> saved\n    note \"done {saved.outcome}\"\n}\n");
        Path golden = dir.resolve("src/test/resources/eval/golden");
        Files.createDirectories(golden);
        Files.writeString(golden.resolve("workflow.yaml"), "- id: w-1\n  input: hello\n");
        Path src = dir.resolve("src/main/java/demo/WriteMarker.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package demo;
                import io.github.llm4j.agent.task.*;
                public class WriteMarker implements Task {
                    @Override public String getName() { return "WriteMarker"; }
                    %s
                    @Override public TaskResult run(TaskContext context) throws Exception {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("marker.file")), "ran");
                        return TaskResult.value("ok");
                    }
                }
                """.formatted(effect));
        Path classes = dir.resolve("target/classes");
        Files.createDirectories(classes.resolve("META-INF/services"));
        Files.writeString(classes.resolve("META-INF/services/io.github.llm4j.agent.task.Task"), "demo.WriteMarker\n");
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", System.getProperty("java.class.path"), "-d", classes.toString(), src.toString())).isZero();
        return script;
    }

    private String eval(Path script) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(out, true));
        try {
            CommandLine cli = WeaveCLI.commandLine();
            cli.setOut(new java.io.PrintWriter(out, true));
            cli.execute("eval", script.toString(), "--mock", "--no-env-file");
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
        return out.toString();
    }

    @Test
    void aTaskThatChangesThingsIsDescribedAndNotRun() throws Exception {
        Path marker = dir.resolve("marker.txt");
        System.setProperty("marker.file", marker.toString());
        try {
            String text = eval(project("")); // the default effect is CHANGES
            assertThat(marker).as("the task must not have written its file in a free run").doesNotExist();
            assertThat(text).contains("tasks that change things were described, not run");
        } finally {
            System.clearProperty("marker.file");
        }
    }

    @Test
    void aTaskThatOnlyReadsStillRuns() throws Exception {
        Path marker = dir.resolve("marker.txt");
        System.setProperty("marker.file", marker.toString());
        try {
            eval(project("@Override public TaskEffect effect() { return TaskEffect.NONE; }"));
            assertThat(marker).as("a pure task is safe to run and is run").exists();
        } finally {
            System.clearProperty("marker.file");
        }
    }
}
