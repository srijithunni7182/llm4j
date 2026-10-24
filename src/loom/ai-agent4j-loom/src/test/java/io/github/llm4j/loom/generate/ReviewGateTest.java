package io.github.llm4j.loom.generate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Severity;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** G2 of spec 12: what the review leaves open, judged by the gate level. */
class ReviewGateTest {

    @TempDir Path dir;

    private GateResult run(Severity soft, ReviewGate.Item... items) throws Exception {
        try (Workspace ws =
                Writer.render(
                        GenerateFixtures.files(),
                        GenerateFixtures.plan(),
                        Writer.Layout.FLAT,
                        new Writer.Stamp("t", "d"),
                        dir)) {
            return new ReviewGate(
                            script -> new ReviewGate.Reviewed(List.of(items), List.of()), soft)
                    .run(ws, GenerateFixtures.plan());
        }
    }

    private static ReviewGate.Item item(Lens lens, Severity severity) {
        return new ReviewGate.Item("X1", lens, severity, 7, "a finding", "do this");
    }

    @Test
    void aHighFindingFailsInAnyLens() throws Exception {
        for (Lens lens :
                new Lens[] {Lens.SECURITY, Lens.COST, Lens.RELIABILITY, Lens.OBSERVABILITY})
            assertThat(run(Severity.MEDIUM, item(lens, Severity.HIGH)).passed())
                    .as(lens.name())
                    .isFalse();
    }

    @Test
    void aMediumFindingFailsOnlyInTheLensesThatMakeAGeneratedWorkflowTidy() throws Exception {
        assertThat(run(Severity.MEDIUM, item(Lens.RELIABILITY, Severity.MEDIUM)).passed())
                .isFalse();
        assertThat(run(Severity.MEDIUM, item(Lens.PROMPTS, Severity.MEDIUM)).passed()).isFalse();
        assertThat(run(Severity.MEDIUM, item(Lens.VALIDITY, Severity.MEDIUM)).passed()).isFalse();
        assertThat(run(Severity.MEDIUM, item(Lens.COST, Severity.MEDIUM)).passed()).isTrue();
        assertThat(run(Severity.MEDIUM, item(Lens.SECURITY, Severity.MEDIUM)).passed()).isTrue();
        assertThat(run(Severity.MEDIUM, item(Lens.RELIABILITY, Severity.LOW)).passed()).isTrue();
    }

    @Test
    void theGateLevelMovesTheLine() throws Exception {
        assertThat(run(Severity.HIGH, item(Lens.RELIABILITY, Severity.MEDIUM)).passed()).isTrue();
        assertThat(run(Severity.LOW, item(Lens.RELIABILITY, Severity.LOW)).passed()).isFalse();
    }

    @Test
    void theFeedbackNamesTheRuleTheLineAndWhatToDo() throws Exception {
        GateResult r = run(Severity.MEDIUM, item(Lens.RELIABILITY, Severity.MEDIUM));
        assertThat(r.problems().get(0).text()).isEqualTo("main.loom:7: [X1] a finding — do this");
    }

    @Test
    void aReviewThatCannotRunIsAFailureNotASilentPass() throws Exception {
        try (Workspace ws =
                Writer.render(
                        GenerateFixtures.files(),
                        GenerateFixtures.plan(),
                        Writer.Layout.FLAT,
                        new Writer.Stamp("t", "d"),
                        dir)) {
            GateResult r =
                    new ReviewGate(
                                    s -> {
                                        throw new IllegalStateException("boom");
                                    },
                                    Severity.MEDIUM)
                            .run(ws, GenerateFixtures.plan());
            assertThat(r.passed()).isFalse();
            assertThat(r.problems().get(0).message()).contains("could not run").contains("boom");
        }
    }
}
