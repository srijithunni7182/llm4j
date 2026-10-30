package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.graph.GraphResult;
import io.github.llm4j.loom.graph.GraphService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A step of a parallel block's branch is named {@code Main/s0/p1} in a run. The graph draws each
 * branch as a lane of its own, so the id has to find that node, not stop at the block.
 */
class StepResolverLanesTest {

    private static final String SOURCE =
            """
            agent A { model: "m" }
            agent B { model: "m" }
            workflow Main(t) {
                parallel {
                    delegate "x {t}" to A -> a on_failure { note "a failed" }
                    delegate "y {t}" to B -> b
                }
            }
            """;

    private StepResolver resolver(Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("main.loom"), SOURCE);
        GraphResult graph = new GraphService().graph(script);
        return new StepResolver(graph);
    }

    @Test
    void aBranchStepResolvesToItsLaneNodeNotToTheBlock(@TempDir Path dir) throws Exception {
        StepResolver r = resolver(dir);

        assertThat(r.resolve("Main/s0")).contains("Main/n1");
        assertThat(r.resolve("Main/s0/p0")).contains("Main/n2");
        assertThat(r.resolve("Main/s0/p1")).contains("Main/n3");
    }

    @Test
    void aFailureHandlerInsideABranchResolvesToTheHandlerNode(@TempDir Path dir) throws Exception {
        assertThat(resolver(dir).resolve("Main/s0/p0/f0")).contains("Main/n4");
    }

    @Test
    void aBranchThatDoesNotExistStopsAtTheBlock(@TempDir Path dir) throws Exception {
        assertThat(resolver(dir).resolve("Main/s0/p7")).contains("Main/n1");
    }

    @Test
    void theBranchesOfAReachedBlockCountAsReachedButItsFailureHandlerDoesNot(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("main.loom"), SOURCE);
        Coverage coverage = new Coverage(new GraphService().graph(script));

        coverage.add(java.util.List.of("Main/s0"));

        assertThat(coverage.ran("Main", "n1")).isTrue();
        assertThat(coverage.missed()).extracting(Coverage.Missed::id).containsExactly("n4");
        assertThat(coverage.percent()).isEqualTo(75);
    }
}
