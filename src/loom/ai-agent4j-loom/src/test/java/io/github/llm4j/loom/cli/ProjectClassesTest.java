package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** A task written in Java is found by weave when the project has been built (target/classes) or --classes says where it is, and the error says what to do when it is not. */
class ProjectClassesTest {

    @TempDir Path dir;

    private ClassLoader before = Thread.currentThread().getContextClassLoader();

    @AfterEach
    void restore() {
        Thread.currentThread().setContextClassLoader(before);
    }

    /** A Maven-shaped project whose script runs the task SaveIt; the task is compiled into target/classes only when {@code built}. */
    private Path project(boolean built) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Path script = dir.resolve("src/main/resources/main.loom");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "workflow Main(text) {\n    run SaveIt(content = text) -> saved\n    note \"{saved.value}\"\n}\n");
        if (built) {
            Path src = dir.resolve("src/main/java/demo/SaveIt.java");
            Files.createDirectories(src.getParent());
            Files.writeString(src, """
                    package demo;
                    import io.github.llm4j.agent.task.*;
                    public class SaveIt implements Task {
                        @Override public String getName() { return "SaveIt"; }
                        @Override public TaskEffect effect() { return TaskEffect.NONE; }
                        @Override public TaskResult run(TaskContext context) { return TaskResult.value("ok"); }
                    }
                    """);
            Path classes = dir.resolve("target/classes");
            Files.createDirectories(classes.resolve("META-INF/services"));
            Files.writeString(classes.resolve("META-INF/services/io.github.llm4j.agent.task.Task"), "demo.SaveIt\n");
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            assertThat(javac.run(null, null, null, "-cp", System.getProperty("java.class.path"), "-d", classes.toString(), src.toString())).isZero();
        }
        return script;
    }

    private String output = "";

    private int check(Path script, String... extra) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine cli = WeaveCLI.commandLine();
        cli.setOut(new java.io.PrintWriter(out, true));
        PrintStream old = System.out;
        System.setOut(new PrintStream(out, true));
        PrintStream oldErr = System.err;
        System.setErr(new PrintStream(out, true));
        try {
            List<String> args = new java.util.ArrayList<>(List.of("check", script.toString(), "--no-env", "--no-env-file"));
            args.addAll(List.of(extra));
            return cli.execute(args.toArray(String[]::new));
        } finally {
            System.setOut(old);
            System.setErr(oldErr);
            output = out.toString();
        }
    }

    @Test
    void anUnbuiltProjectSaysWhyTheTaskIsUnknownAndHowToFixIt() throws Exception {
        assertThat(check(project(false))).isEqualTo(2);
        assertThat(output).contains("unknown task").contains("weave sees only the classes on its class path").contains("mvn compile").contains("--classes");
    }

    @Test
    void aBuiltProjectIsFoundWithNoFlag() throws Exception {
        assertThat(check(project(true))).as(output).isZero();
        assertThat(output).contains("ready to run");
    }

    @Test
    void classesNamesAFolderExplicitlyAndAMissingOneIsRefused() throws Exception {
        Path script = project(true);
        Files.move(dir.resolve("target/classes"), dir.resolve("elsewhere"));
        assertThat(check(script)).as("moved away: not found").isEqualTo(2);
        Thread.currentThread().setContextClassLoader(before);
        assertThat(check(script, "--classes", dir.resolve("elsewhere").toString())).as(output).isZero();
        Thread.currentThread().setContextClassLoader(before);
        assertThat(check(script, "--classes", dir.resolve("nothing-here").toString())).isEqualTo(2);
        assertThat(output).contains("does not exist").contains("mvn compile");
    }
}
