package io.github.llm4j.eval.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R1 and R2.4 of loom-weave-eval: a dataset is a folder, and what is wrong with it is listed, not thrown. */
class EvalDatasetTest {

    @TempDir Path dir;

    private void write(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    @Test
    void r1_1_eachFileIsATargetNamedByItsStemAndTheOthersAreSkipped() throws IOException {
        write("researcher.yaml", "- name: a\n  input: x\n");
        write("workflow.yml", "- name: b\n  input: y\n");
        write("dataset.yaml", "dimensions: {safety: Does no harm}\n");
        write("fixtures.yaml", "- id: f\n  match: q\n  snippets: [s]\n");
        write("notes.txt", "not a dataset file");

        EvalDataset dataset = EvalDataset.load(dir);

        assertThat(dataset.files().keySet()).containsExactly("researcher", "workflow");
        assertThat(dataset.all()).extracting(EvalScenario::name).containsExactly("a", "b");
        assertThat(dataset.problems()).isEmpty();
    }

    @Test
    void r1_2_dimensionsComeFromAMappingOrAList() throws IOException {
        write("dataset.yaml", "dimensions:\n  safety: Does no harm\n  grounding: Uses sources\n");
        assertThat(EvalDataset.load(dir).dimensions()).containsExactly(java.util.Map.entry("safety", "Does no harm"), java.util.Map.entry("grounding", "Uses sources"));

        write("dataset.yaml", "dimensions: [safety, grounding]\n");
        assertThat(EvalDataset.load(dir).dimensions().keySet()).containsExactly("safety", "grounding");
    }

    @Test
    void r1_3_aDimensionTheDatasetDoesNotDeclareIsReportedWithWhatItHas() throws IOException {
        write("dataset.yaml", "dimensions: [safety]\n");
        write("a.yaml", "- name: n\n  input: x\n  dimensions: [safety, speed]\n");

        assertThat(EvalDataset.load(dir).problems()).singleElement().satisfies(p -> {
            assertThat(p.message()).contains("uses the dimension speed").contains("it has safety");
            assertThat(p.fatal()).isFalse();
            assertThat(p.toString()).startsWith("a.yaml [n]");
        });
    }

    @Test
    void withNoDimensionsFileAnyDimensionIsAllowed() throws IOException {
        write("a.yaml", "- name: n\n  input: x\n  dimensions: [anything]\n");

        assertThat(EvalDataset.load(dir).problems()).isEmpty();
    }

    @Test
    void aScenarioWithNoInputAndARepeatedIdAreReported() throws IOException {
        write("a.yaml", "- name: first\n  id: s-1\n  input: x\n- name: empty\n  input: \"  \"\n");
        write("b.yaml", "- name: again\n  id: s-1\n  input: y\n");

        List<EvalDataset.Problem> problems = EvalDataset.load(dir).problems();

        assertThat(problems).extracting(EvalDataset.Problem::message).anyMatch(m -> m.equals("has no input"))
                .anyMatch(m -> m.contains("repeats the id s-1 (first in a.yaml)"));
    }

    @Test
    void aFileThatCannotBeReadIsAFatalProblemAndTheOthersStillLoad() throws IOException {
        write("good.yaml", "- name: ok\n  input: x\n");
        write("bad.yaml", "- name: [unclosed\n");

        EvalDataset dataset = EvalDataset.load(dir);

        assertThat(dataset.files().keySet()).containsExactly("good");
        assertThat(dataset.problems()).singleElement().satisfies(p -> {
            assertThat(p.fatal()).isTrue();
            assertThat(p.file()).isEqualTo("bad.yaml");
            assertThat(p.message()).startsWith("cannot be read");
        });
    }

    @Test
    void anUnknownKeyInAScenarioIsAProblemNotASilentIgnore() throws IOException {
        write("a.yaml", "- name: n\n  input: x\n  rubrik: [typo]\n");

        assertThat(EvalDataset.load(dir).problems()).singleElement().satisfies(p -> {
            assertThat(p.fatal()).isTrue();
            assertThat(p.message()).contains("rubrik");
        });
    }

    @Test
    void aMissingFolderIsOneFatalProblem() {
        EvalDataset dataset = EvalDataset.load(dir.resolve("nope"));

        assertThat(dataset.files()).isEmpty();
        assertThat(dataset.problems()).singleElement().extracting(EvalDataset.Problem::message).isEqualTo("the dataset folder does not exist");
    }

    @Test
    void aBadDimensionsFileIsReported() throws IOException {
        write("dataset.yaml", "dimensions: 42\n");

        assertThat(EvalDataset.load(dir).problems()).singleElement().extracting(EvalDataset.Problem::message).asString().contains("needs a dimensions: mapping");
    }

    @Test
    void r2_4_fromDirectoryGivesTheFilesByNameAndThrowsForAFolderItCannotRead() throws IOException {
        write("a.yaml", "- name: n\n  input: x\n");

        assertThat(EvalScenarios.fromDirectory(dir)).containsOnlyKeys("a");
        assertThatThrownBy(() -> EvalScenarios.fromDirectory(dir.resolve("nope"))).isInstanceOf(EvalDatasetException.class).hasMessageContaining("not found");
        write("b.yaml", "- [broken\n");
        assertThatThrownBy(() -> EvalScenarios.fromDirectory(dir)).isInstanceOf(EvalDatasetException.class).hasMessageContaining("b.yaml");
    }
}
