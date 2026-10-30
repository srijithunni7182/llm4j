package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 19 R5: {@code weave eval --emit-test} writes the opt-in JUnit class for a project that
 * wants the Java API. Its hermetic test runs the wiring on a mock model; its real test runs only
 * with {@code -Deval.real=true} and under a cap. The class is compiled and its hermetic test run
 * here, so what the command writes is known to work.
 */
class EvalEmitTestTest {
    @TempDir Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private Path project() throws Exception {
        Path script = Files.writeString(dir.resolve("main.loom"), "agent Helper { model: \"m\" system: \"You help.\" }\nworkflow Main(t) { delegate \"{t}\" to Helper -> r\n return r }\n");
        Path golden = Files.createDirectories(dir.resolve("eval/golden"));
        Files.writeString(golden.resolve("helper.yaml"), "- {id: a, input: q, expected_output_contains: x}\n");
        return script;
    }

    private int emit(Path script, Path target) {
        WeaveEnv env =
                new WeaveEnv(
                        m -> null,
                        q -> "yes",
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                        List.of("weave"),
                        k -> null);
        EvalCommand c = new EvalCommand();
        new picocli.CommandLine(c).parseArgs(script.toString(), "--emit-test", target.toString());
        return EvalCommand.eval(c, env);
    }

    @Test
    void theClassIsWrittenWithThePackageOfItsFolderAndNothingIsRun() throws Exception {
        Path script = project();
        Path target = dir.resolve("src/test/java/acme/flow/RealEvaluationTest.java");

        assertThat(emit(script, target)).isZero();

        String text = Files.readString(target);
        assertThat(text).startsWith("package acme.flow;").contains("class RealEvaluationTest").contains("Boolean.getBoolean(\"eval.real\")").contains("--max-cost").contains("--mock");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Wrote").contains("-Deval.real=true");
    }

    @Test
    void anExistingFileIsNeverOverwritten() throws Exception {
        Path script = project();
        Path target = Files.writeString(dir.resolve("Keep.java"), "keep");

        assertThat(emit(script, target)).isEqualTo(2);
        assertThat(Files.readString(target)).isEqualTo("keep");
    }

    @Test
    void theWrittenClassCompilesAndItsHermeticTestPassesOnAMockModel() throws Exception {
        Path script = project();
        Path target = dir.resolve("gen/RealEvaluationTest.java");
        assertThat(emit(script, target)).isZero();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path classes = Files.createDirectories(dir.resolve("classes"));

        int code = compiler.run(null, null, null, "-cp", System.getProperty("java.class.path"), "-d", classes.toString(), target.toString());

        assertThat(code).as("the generated class compiles").isZero();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> generated = loader.loadClass("RealEvaluationTest");
            var constructor = generated.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object instance = constructor.newInstance();
            Method hermetic = generated.getDeclaredMethod("theWiringRunsOnAMockModelAtNoCost");
            hermetic.setAccessible(true);
            hermetic.invoke(instance);
            Method real = generated.getDeclaredMethod("aRealEvaluationRunsOnlyWhenAskedAndStaysUnderACap");
            real.setAccessible(true);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
                try {
                    real.invoke(instance);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            }).isInstanceOf(org.opentest4j.TestAbortedException.class).hasMessageContaining("-Deval.real=true");
        }
    }
}
