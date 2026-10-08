package io.github.llm4j.eval.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalScenariosTest {

    @TempDir private Path tempDir;

    @Test
    void fromYamlResource_parsesEveryFieldOfEveryRow() {
        List<EvalScenario> scenarios = EvalScenarios.fromYamlResource("sample-scenarios.yaml");

        assertThat(scenarios).hasSize(2);

        EvalScenario first = scenarios.get(0);
        assertThat(first.name()).isEqualTo("percentage-calculation");
        assertThat(first.input()).isEqualTo("What is 15% of 240?");
        assertThat(first.expectedOutputContains()).isEqualTo("36");
        assertThat(first.expectedTools()).containsExactly("calculator");

        EvalScenario second = scenarios.get(1);
        assertThat(second.name()).isEqualTo("capital-lookup");
        assertThat(second.expectedOutputContains()).isEqualTo("Paris");
        assertThat(second.expectedTools()).isNull();
    }

    @Test
    void fromYamlResource_throwsWhenResourceMissing() {
        assertThatThrownBy(() -> EvalScenarios.fromYamlResource("does-not-exist.yaml"))
                .isInstanceOf(EvalDatasetException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void fromYaml_throwsOnMalformedYaml() {
        InputStream malformed =
                new ByteArrayInputStream(
                        "not: [a, valid, scenario, list".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> EvalScenarios.fromYaml(malformed))
                .isInstanceOf(EvalDatasetException.class);
    }

    @Test
    void fromYaml_pathOverload_readsRealFile() throws IOException {
        Path yamlFile = tempDir.resolve("scenarios.yaml");
        Files.writeString(
                yamlFile,
                "- name: from-file\n  input: \"hello\"\n  expectedOutputContains: \"hi\"\n");

        List<EvalScenario> scenarios = EvalScenarios.fromYaml(yamlFile);

        assertThat(scenarios).hasSize(1);
        assertThat(scenarios.get(0).name()).isEqualTo("from-file");
    }

    @Test
    void fromYaml_pathOverload_throwsWhenFileMissing() {
        Path missing = tempDir.resolve("does-not-exist.yaml");
        assertThatThrownBy(() -> EvalScenarios.fromYaml(missing))
                .isInstanceOf(EvalDatasetException.class)
                .hasMessageContaining("Failed to read golden dataset");
    }

    @Test
    void scenarioToStringUsesNameForDisplay() {
        EvalScenario named = new EvalScenario("my-scenario", "input", null, null, null, null, null);
        assertThat(named.toString()).isEqualTo("my-scenario");

        EvalScenario unnamed = new EvalScenario(null, "raw input", null, null, null, null, null);
        assertThat(unnamed.toString()).isEqualTo("raw input");
    }
}
