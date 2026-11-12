package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.ControlledChildScheduler;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Phase 33c: a child has its own required budget, charged to the parent, with reservation (D11, guard G4). */
class ChildBudgetTest {

    /** Every model call answers at once and uses 15 tokens. */
    private static LLMClient client(String model) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                return LLMResponse.builder()
                        .content("```json\n{\"thought\": \"t\", \"final_answer\": \"done\"}\n```")
                        .model(model)
                        .tokenUsage(10, 5, 15)
                        .build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
    }

    private static HarnessExecutor executor(String source) {
        Map<String, RunJournal> journals = new ConcurrentHashMap<>();
        HarnessExecutor e =
                new HarnessExecutor(
                        StartParseTest.parse(source), new ToolRegistry(), ChildBudgetTest::client);
        e.setRunId("p");
        e.setJournal(journals.computeIfAbsent("p", k -> RunJournal.inMemory()));
        e.setChildRuns(id -> journals.computeIfAbsent(id, k -> RunJournal.inMemory()));
        e.setChildScheduler(new ControlledChildScheduler());
        e.initialize();
        return e;
    }

    private static final String AGENT = "agent R { model: \"m\" system: \"s\" }\n";

    private static final String ONE_CALL = "workflow One(x) { delegate \"go\" to R -> a  return a }\n";
    private static final String TWO_CALLS =
            "workflow Two(x) { delegate \"go\" to R -> a  delegate \"again\" to R -> b  return b }\n";

    @Test
    void whatAChildSpendsCountsAgainstTheParentsBudget() {
        HarnessExecutor e =
                executor(
                        "budget { tokens: 1000 }\n"
                                + AGENT
                                + ONE_CALL
                                + """
                                workflow Main(q) {
                                    start One(x = q) budget { tokens: 100 } -> work
                                    wait work timeout 1m -> out
                                }
                                """);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(e.getRunBudget().spent().tokens()).isEqualTo(15);
    }

    @Test
    void aChildThatRunsOutOfBudgetEndsFailedWithTheBudgetAsTheCategory() {
        HarnessExecutor e =
                executor(
                        "budget { tokens: 1000 }\n"
                                + AGENT
                                + TWO_CALLS
                                + """
                                workflow Main(q) {
                                    start Two(x = q) budget { tokens: 20 } -> work
                                    wait work timeout 1m -> out
                                    set seen = work.status
                                    set why = work.failure
                                }
                                """);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(e.getContext().getVariable("seen")).isEqualTo("failed");
        assertThat(String.valueOf(e.getContext().getVariable("why"))).startsWith("budget:");
    }

    @Test
    void aStartIsRefusedWhenTheParentCannotCoverItAfterTheOtherChildrenCaps() {
        HarnessExecutor e =
                executor(
                        "budget { tokens: 1000 }\n"
                                + AGENT
                                + ONE_CALL
                                + """
                                workflow Main(q) {
                                    start One(x = q) budget { tokens: 600 } -> a
                                    start One(x = q) budget { tokens: 600 } -> b
                                }
                                """);

        assertThatThrownBy(() -> e.executeWorkflow("Main", Map.of("q", "x")))
                .hasMessageContaining("reserve")
                .hasMessageContaining("600")
                .hasMessageContaining("tokens");
    }

    @Test
    void aSettledChildReleasesWhatItDidNotSpend() {
        HarnessExecutor e =
                executor(
                        "budget { tokens: 1000 }\n"
                                + AGENT
                                + ONE_CALL
                                + """
                                workflow Main(q) {
                                    start One(x = q) budget { tokens: 600 } -> a
                                    wait a timeout 1m -> ra
                                    start One(x = q) budget { tokens: 600 } -> b
                                    wait b timeout 1m -> rb
                                }
                                """);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(String.valueOf(e.getContext().getVariable("rb"))).contains("done");
        assertThat(e.getRunBudget().spent().tokens()).isEqualTo(30);
    }

    @Test
    void parallelBranchesStartingAtTheSameTimeCannotBothTakeTheLastOfTheParentsBudget() {
        for (int round = 0; round < 25; round++) {
            Map<String, RunJournal> journals = new ConcurrentHashMap<>();
            HarnessExecutor e =
                    new HarnessExecutor(
                            StartParseTest.parse(
                                    "budget { tokens: 1000 }\n"
                                            + AGENT
                                            + ONE_CALL
                                            + """
                                            workflow Main(q) {
                                                parallel {
                                                    start One(x = q) budget { tokens: 400 } -> a
                                                    start One(x = q) budget { tokens: 400 } -> b
                                                    start One(x = q) budget { tokens: 400 } -> c
                                                }
                                            }
                                            """),
                            new ToolRegistry(),
                            ChildBudgetTest::client);
            e.setRunId("p");
            e.setJournal(journals.computeIfAbsent("p", k -> RunJournal.inMemory()));
            e.setChildRuns(id -> journals.computeIfAbsent(id, k -> RunJournal.inMemory()));
            e.setChildScheduler(new ControlledChildScheduler());
            e.initialize();

            assertThatThrownBy(() -> e.executeWorkflow("Main", Map.of("q", "x")))
                    .hasMessageContaining("cannot reserve");
            assertThat(journals.keySet())
                    .as("the parent and two children, never three (round " + round + ")")
                    .hasSize(3);
        }
    }
}
