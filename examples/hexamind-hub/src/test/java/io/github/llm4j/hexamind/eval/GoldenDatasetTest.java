package io.github.llm4j.hexamind.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Keeps the golden dataset loadable and consistent. Offline and free: runs in the normal build. */
class GoldenDatasetTest {

    @Test
    void everyFileLoadsAndTheCountsAreWhatTheSpecSays() {
        assertThat(GoldenDataset.AGENTS).allSatisfy(a -> assertThat(GoldenDataset.agent(a)).hasSize(8));
        assertThat(GoldenDataset.prompts()).hasSize(12);
        assertThat(GoldenDataset.workflow()).hasSize(10);
        assertThat(GoldenDataset.all()).hasSize(70);
    }

    @Test
    void idsAreUniqueAndMatchTheirFile() {
        Set<String> seen = new HashSet<>();
        for (String a : GoldenDataset.AGENTS) {
            GoldenDataset.agent(a).forEach(s -> {
                assertThat(s.id()).startsWith(a + "-");
                assertThat(seen.add(s.id())).as(s.id()).isTrue();
            });
        }
        GoldenDataset.prompts().forEach(s -> {
            assertThat(s.id()).startsWith("prompt-");
            assertThat(seen.add(s.id())).as(s.id()).isTrue();
        });
        GoldenDataset.workflow().forEach(s -> {
            assertThat(s.id()).startsWith("flow-");
            assertThat(seen.add(s.id())).as(s.id()).isTrue();
        });
    }

    @Test
    void everyScenarioHasInputKnownDimensionsTagsAndRubric() {
        for (EvalScenario s : GoldenDataset.all()) {
            assertThat(s.input()).as(s.id() + " input").isNotBlank();
            assertThat(s.dimensions()).as(s.id() + " dimensions").isNotEmpty().isSubsetOf(GoldenDataset.DIMENSIONS);
            assertThat(GoldenDataset.tag(s, "family")).as(s.id() + " family").isIn("reasoning", "prompts", "workflows");
            assertThat(GoldenDataset.rubric(s)).as(s.id() + " rubric").isNotEmpty();
            if (s.expectedTools() != null) {
                assertThat(s.expectedTools()).as(s.id() + " tools").isSubsetOf(GoldenDataset.TOOLS);
            }
        }
    }

    @Test
    void agentScenariosCarryTheirPersonaAndFabricatedPremisesCarryAFixture() {
        for (String a : GoldenDataset.AGENTS) {
            for (EvalScenario s : GoldenDataset.agent(a)) {
                assertThat(GoldenDataset.tag(s, "agent")).isEqualTo(a);
                assertThat(GoldenDataset.lines(s, "PERSONA:")).as(s.id()).isNotEmpty();
                if ("fabricated-premise".equals(GoldenDataset.tag(s, "kind"))) {
                    assertThat(s.retrievalContext()).as(s.id() + " fixture").isNotEmpty();
                    assertThat(s.expectedTools()).contains("WebSearch");
                }
            }
        }
    }

    @Test
    void everyPromptScenarioNamesAPromptThatExists() throws Exception {
        String prompts = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/prompts.yaml"));
        for (EvalScenario s : GoldenDataset.prompts()) {
            List<String> ids = GoldenDataset.lines(s, "PROMPT:");
            assertThat(ids).as(s.id()).hasSize(1);
            assertThat(prompts).as(s.id()).contains("  " + ids.get(0) + ":");
        }
    }

    @Test
    void workflowScenariosDeclareAnExpectedPath() {
        for (EvalScenario s : GoldenDataset.workflow()) {
            assertThat(GoldenDataset.lines(s, "EXPECTED PATH:")).as(s.id()).hasSize(1);
        }
    }
}
