package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R1.1 and R1.3 of loom-weave-eval: files are matched to agents and workflows, and a file that matches nothing is named with what is near. */
class DatasetFolderTest {

    @TempDir Path dir;

    private static LoomScript script(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    private static final String ONE = """
            agent Researcher { model: "m" system: "s" }
            agent Writer { model: "m" system: "s" }
            workflow Main(topic) { delegate "x" to Researcher -> r }
            """;

    private void file(String name) throws IOException {
        Files.writeString(dir.resolve(name), "- name: n\n  input: x\n");
    }

    @Test
    void theDefaultFolderIsEvalGoldenBesideTheScript() {
        assertThat(DatasetFolder.locate(Path.of("/p/main.loom"), null)).isEqualTo(Path.of("/p/eval/golden"));
        assertThat(DatasetFolder.locate(Path.of("/p/main.loom"), Path.of("/q/data"))).isEqualTo(Path.of("/q/data"));
    }

    @Test
    void anAgentFileMatchesWhateverTheCaseAndAWorkflowFileMatchesItsName() throws IOException {
        file("researcher.yaml");
        file("WRITER.yaml");
        file("Main.yaml");

        var plan = DatasetFolder.plan(script(ONE), dir, null);

        assertThat(plan.ok()).isTrue();
        assertThat(plan.targets()).extracting(t -> t.kind() + ":" + t.name()).containsExactlyInAnyOrder("workflow:Main", "agent:Researcher", "agent:Writer");
    }

    @Test
    void workflowYamlIsForMainThenForTheOnlyWorkflowThenForTheOneNamed() throws IOException {
        file("workflow.yaml");
        assertThat(DatasetFolder.plan(script(ONE), dir, null).targets()).extracting(DatasetFolder.Target::name).containsExactly("Main");

        String two = "agent A { model: \"m\" system: \"s\" }\nworkflow First() { delegate \"x\" to A -> r }\nworkflow Second() { delegate \"x\" to A -> r }\n";
        var ambiguous = DatasetFolder.plan(script(two), dir, null);
        assertThat(ambiguous.problems()).singleElement().asString().contains("not clear which workflow").contains("First, Second").contains("--workflow");
        assertThat(DatasetFolder.plan(script(two), dir, "second").targets()).extracting(DatasetFolder.Target::name).containsExactly("Second");

        String lone = "agent A { model: \"m\" system: \"s\" }\nworkflow Only() { delegate \"x\" to A -> r }\n";
        assertThat(DatasetFolder.plan(script(lone), dir, null).targets()).extracting(DatasetFolder.Target::name).containsExactly("Only");
    }

    @Test
    void aFileThatMatchesNothingIsReportedWithTheNearestNames() throws IOException {
        file("reseacher.yaml");
        file("zebra.yaml");

        var plan = DatasetFolder.plan(script(ONE), dir, null);

        assertThat(plan.ok()).isFalse();
        assertThat(plan.problems()).anyMatch(p -> p.startsWith("reseacher.yaml") && p.contains("did you mean Researcher?"))
                .anyMatch(p -> p.startsWith("zebra.yaml") && p.contains("it has Researcher, Writer, Main"));
        assertThat(plan.targets()).isEmpty();
    }

    @Test
    void datasetProblemsComeThroughToo() throws IOException {
        Files.writeString(dir.resolve("researcher.yaml"), "- name: n\n  input: \"\"\n");

        assertThat(DatasetFolder.plan(script(ONE), dir, null).problems()).anyMatch(p -> p.contains("has no input"));
    }

    @Test
    void aTargetKnowsHowManyScenariosItHas() throws IOException {
        Files.writeString(dir.resolve("researcher.yaml"), "- {name: a, input: x}\n- {name: b, input: y}\n");

        var plan = DatasetFolder.plan(script(ONE), dir, null);

        assertThat(plan.scenarioCount()).isEqualTo(2);
        assertThat(plan.targets().get(0).isAgent()).isTrue();
    }
}
