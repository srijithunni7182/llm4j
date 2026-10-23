package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.init.TemplateWriter;
import io.github.llm4j.loom.init.Templates;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * R32: the gates are a bar for hand-written projects too; the samples and every starter template
 * meet it.
 */
class GateOnlyExamplesTest {

    @TempDir Path dir;

    static Stream<String> samples() {
        return Stream.of(
                "samples/boardroom", "samples/content_factory", "samples/digest/digest.loom");
    }

    @ParameterizedTest
    @MethodSource("samples")
    void everySamplePassesTheGatesThatCanRunOnIt(String where) {
        var o = GenerateCommandTest.generate(GenerateCommandTest.noModels(), "--gate-only", where);
        assertThat(o.out()).contains("✓ G1 check");
        assertThat(o.code()).as(o.out()).isZero();
    }

    static Stream<String> templates() {
        return Templates.all().stream().map(Templates.Template::name);
    }

    @ParameterizedTest
    @MethodSource("templates")
    void everyStarterTemplatePassesToo(String name) throws Exception {
        Path project = dir.resolve(name);
        var t = Templates.find(name).orElseThrow();
        TemplateWriter.write(
                t.name(),
                t.files(),
                project,
                Map.of("name", name, "artifact", name, "testScript", "main.loom"));
        assertThat(Files.isRegularFile(project.resolve("main.loom"))).isTrue();
        var o =
                GenerateCommandTest.generate(
                        GenerateCommandTest.noModels(), "--gate-only", project.toString());
        assertThat(o.code()).as(o.out()).isZero();
    }
}
