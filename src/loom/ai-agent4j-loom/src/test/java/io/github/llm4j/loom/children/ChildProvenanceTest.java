package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.eval.MockModels;
import io.github.llm4j.loom.execution.ControlledChildScheduler;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.runtime.RunJournal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Guard G9 of phase 33 (D15): provenance crosses the boundary of a started workflow in both
 * directions. A canary in a {@code start} argument and in a child's result is still seen as model
 * text on the other side, at every exit a value leaves by.
 */
class ChildProvenanceTest {

    private static final String SCRIPT =
            """
            agent W { model: "m" system: "s" }
            workflow Child(topic) { return topic }
            workflow Main(q) {
                delegate "write" to W -> draft
                start Child(topic = draft) budget { tokens: 100000 } -> work
                wait work timeout 1m -> out
                return out
            }
            """;

    @Test
    void aCanaryInAStartArgumentAndInAChildsResultIsModelWrittenAtEveryExit() {
        List<String> seen = new ArrayList<>();
        List<String> leaks = new ArrayList<>();
        Map<String, RunJournal> journals = new ConcurrentHashMap<>();
        HarnessExecutor parent =
                new HarnessExecutor(StartParseTest.parse(SCRIPT), new ToolRegistry(), new MockModels());
        parent.setRunId("p");
        parent.setJournal(journals.computeIfAbsent("p", k -> RunJournal.inMemory()));
        parent.setChildRuns(id -> journals.computeIfAbsent(id, k -> RunJournal.inMemory()));
        parent.setChildScheduler(new ControlledChildScheduler());
        parent.setSinkOracle(
                (sink, value, modelWritten) -> {
                    seen.add(sink);
                    if (String.valueOf(value).contains("mock") && !modelWritten) leaks.add(sink + " = " + value);
                });
        parent.initialize();

        parent.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(seen).as("the child's return and the parent's return were both watched").hasSizeGreaterThanOrEqualTo(2);
        assertThat(leaks).as("a canary called not model-written").isEmpty();
    }
}
