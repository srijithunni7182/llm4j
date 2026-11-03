package io.github.llm4j.loom.loop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.wtest.TestRunner;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 25, R7 (D44): a run that failed because a planner's list was over its bound becomes a test
 * case that keeps failing the same way, so the next prompt or model change is checked against it.
 */
class CapturedTooManyRunBecomesCaseTest {

    @TempDir Path dir;

    static final String SCRIPT =
            """
            agent Planner { model: "m"  system: "You plan."  output_schema: { tasks: list<string> } }
            agent Worker { model: "m"  system: "You work." }
            workflow Main(goal) {
                delegate "Plan {goal}" to Planner -> plan
                for each t in plan.tasks max 3 {
                    delegate "{t}" to Worker -> r
                }
                return "done"
            }
            """;

    @Test
    void aRunThatWentOverItsBoundIsCapturedAsACaseThatFailsTheSameWay() throws Exception {
        Path script = Runs.project(dir.resolve("proj"), SCRIPT);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var body = mapper.createObjectNode();
        body.putObject("input").put("goal", "ship it");
        var planner = body.putObject("models").putObject("Planner");
        var tasks = planner.putArray("tasks");
        for (int i = 1; i <= 5; i++) tasks.add("task" + i);
        Path run = Runs.run(dir.resolve("store"), "r-over", script, body, Map.of("goal", "ship it"));

        Capture.Captured c = Capture.capture(StoreReader.open(run), Capture.Options.defaults(), true);

        assertThat(c.written()).isTrue();
        assertThat(c.text()).contains("Planner").contains("task5");
        var suite = TestRunner.load(StoreReader.open(run).script(), List.of(c.file()));
        assertThat(suite.problems()).isEmpty();
        var results = TestRunner.run(suite, null, false, false);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).failures()).isEmpty();
        assertThat(results.get(0).passed()).isTrue();
        // the worker never ran: the case expects no worker calls, as the run did
        assertThat(c.text()).doesNotContain("Worker:");
    }
}
