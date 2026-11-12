package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.children.ChildHarness;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Guard G21, provenance (D6, D14): what a child reports is {@code unchecked} when the parent reads it. */
class ChildProgressProvenanceTest {

    @Test
    void aValueTheParentCopiesFromAChildsProgressIsUnchecked() {
        ChildHarness h = new ChildHarness();
        HarnessExecutor e =
                h.parent(
                        """
                        workflow Research(topic) progress { stage: string } { report { stage: "reading" } }
                        workflow Main(q) {
                            start Research(topic = q) budget { tokens: 10 } -> work
                            wait work timeout 1m -> out
                            set stage = work.progress.stage
                            set fixed = "plain"
                        }
                        """,
                        new ControlledChildScheduler());
        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(e.tagOf("stage").level()).isEqualTo(Provenance.UNCHECKED.level());
        assertThat(e.tagOf("fixed").level()).as("a plain text of the script is not model text").isEqualTo(Provenance.NOT_MODEL.level());
    }
}
