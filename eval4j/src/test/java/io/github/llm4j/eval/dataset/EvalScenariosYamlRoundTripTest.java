package io.github.llm4j.eval.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalScenariosYamlRoundTripTest {

    private static List<EvalScenario> trickyScenarios() {
        return List.of(
                new EvalScenario("plain", "What is 2+2?", "4", null, null, null, null),
                new EvalScenario(
                        "yaml-lookalikes",
                        "true",
                        "null",
                        "123",
                        List.of("yes", "no", "~", "0x1F", "1e3"),
                        List.of("a: b", "# not a comment", "- dash", "key: [x, y]", "'quoted'", "\"dq\""),
                        List.of("")),
                new EvalScenario(
                        "multi-line",
                        "line one\nline two\n\nline four with trailing space ",
                        null,
                        "answer\nwith\nnewlines\n",
                        null,
                        null,
                        null),
                new EvalScenario("unicode", "Café ☕ — naïve 你好 🚀", "日本語", null, null, null, null),
                new EvalScenario(null, "no name, colon: inside", null, null, null, null, null),
                new EvalScenario("long", "x".repeat(5000), null, null, null, null, null));
    }

    @Test
    void roundTripPreservesEverythingIncludingNullsAndTrickyStrings() {
        List<EvalScenario> original = trickyScenarios();
        String yaml = EvalScenarios.toYaml(original);
        List<EvalScenario> back =
                EvalScenarios.fromYaml(new java.io.ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        assertThat(back).usingRecursiveComparison().isEqualTo(original);
    }

    @Test
    void outputIsStableAndOmitsNulls() {
        String first = EvalScenarios.toYaml(trickyScenarios());
        String second = EvalScenarios.toYaml(trickyScenarios());
        assertThat(first).isEqualTo(second);
        assertThat(EvalScenarios.toYaml(List.of(new EvalScenario("n", "in", null, null, null, null, null))))
                .doesNotContain("null")
                .doesNotContain("expectedOutput")
                .doesNotStartWith("---");
    }

    @Test
    void writesFileAtomicallyAndLoadsBack(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("nested/dir/generated.yaml");
        EvalScenarios.toYaml(trickyScenarios(), file);
        assertThat(EvalScenarios.fromYaml(file)).usingRecursiveComparison().isEqualTo(trickyScenarios());
        try (var files = Files.list(file.getParent())) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void emptyListRoundTrips() {
        String yaml = EvalScenarios.toYaml(List.of());
        assertThat(EvalScenarios.fromYaml(new java.io.ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))))
                .isEmpty();
    }
}
