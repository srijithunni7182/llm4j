package io.github.llm4j.loom.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 25, R7 (M11, D44): {@code for each … max N} bounds a fan-out. A list longer than the bound
 * fails the workflow before any item starts, unless {@code on_too_many} says what to do, and the
 * failure points at the agent that wrote the list.
 */
class ForEachMaxTest {

    static final String HEAD =
            """
            agent Planner { model: "test" system: "You plan." output_schema: { tasks: list<string> } }
            agent Worker { model: "test" system: "You work." }
            """;

    static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    // ---- parsing

    @Test
    void maxAndOnTooManyParseOnSequentialAndParallelForEach() {
        LoomScript script =
                parse(
                        HEAD
                                + """
                workflow Main() {
                    delegate "plan" to Planner -> plan
                    for each t in plan.tasks max 10 {
                        delegate "{t}" to Worker -> r
                    } on_too_many {
                        halt "too many"
                    }
                    parallel for each t in plan.tasks max 4 {
                        delegate "{t}" to Worker -> r2
                    }
                }
                """);

        var statements = script.getWorkflows().get(0).getStatements();
        ForEachStmt sequential = (ForEachStmt) statements.get(1);
        ForEachStmt parallel = (ForEachStmt) statements.get(2);
        assertThat(sequential.getMax()).isEqualTo(10);
        assertThat(sequential.getOnTooMany()).hasSize(1);
        assertThat(parallel.getMax()).isEqualTo(4);
        assertThat(parallel.isParallel()).isTrue();
        assertThat(parallel.getOnTooMany()).isEmpty();
    }

    @Test
    void aForEachWithoutMaxIsUnchanged() {
        ForEachStmt each =
                (ForEachStmt)
                        parse(HEAD + "workflow Main() { delegate \"p\" to Planner -> plan\n for each t in plan.tasks { note \"{t}\" } }")
                                .getWorkflows()
                                .get(0)
                                .getStatements()
                                .get(1);

        assertThat(each.getMax()).isZero();
    }

    @Test
    void maxMustBeAtLeastOneAndOnTooManyNeedsAMax() {
        assertThatThrownBy(() -> parse(HEAD + "workflow Main() { for each t in plan.tasks max 0 { note \"x\" } }"))
                .hasMessageContaining("max must be at least 1");
        assertThatThrownBy(
                        () ->
                                parse(
                                        HEAD
                                                + "workflow Main() { for each t in plan.tasks { note \"x\" } on_too_many { halt \"y\" } }"))
                .hasMessageContaining("on_too_many")
                .hasMessageContaining("max");
    }

    // ---- running

    record Run(List<String> notes, List<TraceEvent> events, Throwable error, int workerCalls) {}

    static Run run(int tasks, String workflowBody) {
        StringBuilder list = new StringBuilder();
        for (int i = 1; i <= tasks; i++) list.append(i > 1 ? "," : "").append("\"task").append(i).append('"');
        String plan = "{\"tasks\": [" + list + "]}";
        int[] workers = {0};
        CapturingClient client =
                new CapturingClient("worked") {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        String text = CapturingClient.text(request);
                        if (text.contains("You plan.")) {
                            return LLMResponse.builder().content(plan).model("test").build();
                        }
                        workers[0]++;
                        return LLMResponse.builder().content("worked").model("test").build();
                    }
                };
        LoomScript script = parse(HEAD + workflowBody);
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), model -> client);
        List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
        executor.addTraceListener(events::add);
        executor.initialize();
        Throwable error = null;
        try {
            executor.executeWorkflow("Main", Map.of());
        } catch (Throwable t) {
            error = t;
        }
        List<String> notes =
                events.stream().filter(e -> e.type().equals(TraceEvent.NOTE)).map(TraceEvent::text).toList();
        return new Run(notes, events, error, workers[0]);
    }

    static final String SEQUENTIAL =
            """
            workflow Main() {
                delegate "plan" to Planner -> plan
                for each t in plan.tasks max 10 {
                    delegate "{t}" to Worker -> r
                }
                note "done"
            }
            """;

    @Test
    void aListWithinTheBoundRunsEveryItem() {
        Run r = run(10, SEQUENTIAL);

        assertThat(r.error()).isNull();
        assertThat(r.workerCalls()).isEqualTo(10);
        assertThat(r.notes()).contains("done");
    }

    @Test
    void aListOverTheBoundFailsBeforeAnyItemAndNamesTheAgentAndBothLines() {
        Run r = run(12, SEQUENTIAL);

        assertThat(r.workerCalls()).isZero(); // nothing is half done
        assertThat(r.error()).isNotNull();
        assertThat(r.error().getMessage())
                .contains("plan.tasks has 12 items")
                .contains("agent Planner")
                .contains("line 4") // the planner's step
                .contains("at most 10")
                .contains("line 5") // the for each
                .contains("upstream");
        assertThat(r.notes()).doesNotContain("done");
    }

    @Test
    void theTraceEventHasTheCountsAndTheProducerButNoContent() {
        Run r = run(12, SEQUENTIAL);

        TraceEvent event =
                r.events().stream().filter(e -> e.type().equals("fanout_exceeded")).findFirst().orElseThrow();
        assertThat(event.data())
                .containsEntry("count", 12)
                .containsEntry("max", 10)
                .containsEntry("producer", "Planner")
                .containsEntry("producerLine", 4);
        assertThat(String.valueOf(event.data())).doesNotContain("task1").doesNotContain("task12");
    }

    @Test
    void onTooManyRunsInsteadOfTheItemsWithTheCountAndTheBound() {
        Run r =
                run(
                        12,
                        """
                workflow Main() {
                    delegate "plan" to Planner -> plan
                    for each t in plan.tasks max 10 {
                        delegate "{t}" to Worker -> r
                    } on_too_many {
                        note "got {_count}, takes {_max}"
                    }
                    note "after"
                }
                """);

        assertThat(r.error()).isNull();
        assertThat(r.workerCalls()).isZero();
        assertThat(r.notes()).containsSequence("got 12, takes 10", "after");
    }

    @Test
    void aParallelForEachIsBoundedTheSameWay() {
        Run r =
                run(
                        12,
                        """
                workflow Main() {
                    delegate "plan" to Planner -> plan
                    parallel for each t in plan.tasks max 10 {
                        delegate "{t}" to Worker -> r
                    }
                }
                """);

        assertThat(r.workerCalls()).isZero();
        assertThat(r.error().getMessage()).contains("12 items").contains("agent Planner");
    }
}
