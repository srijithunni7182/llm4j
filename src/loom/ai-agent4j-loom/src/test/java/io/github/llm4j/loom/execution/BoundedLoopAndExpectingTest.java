package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Loops can be bounded (`max N` + `on_exhausted`); a step can ask an agent for a different schema (`expecting`). */
class BoundedLoopAndExpectingTest {

    private static final String SCRIPT = """
            agent Boss {
                model: "test"
                system: "You are Boss."
                output_schema: { plan: string }
            }

            agent Worker {
                model: "test"
                system: "You are Worker."
            }

            workflow Main(goal) {
                delegate "Plan {goal}" to Boss -> plan
                loop until (review.verdict == "DONE") max 3 {
                    delegate "Review round {_loopRound}" to Boss -> review expecting { verdict: enum["DONE", "AGAIN"], fix: string }
                    alt (review.verdict == "AGAIN") {
                        delegate "Fix: {review.fix}" to Worker -> work
                    }
                } on_exhausted {
                    delegate "Escalate after {_loopRounds} rounds" to Worker -> escalation
                }
            }
            """;

    private static LLMClientFactory factory(int doneOnRound, List<String> tasks, AtomicInteger reviews) {
        return model -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                String system = request.getMessages().get(0).getContent();
                String user = request.getMessages().get(request.getMessages().size() - 1).getContent();
                tasks.add(user);
                String content;
                if (system.startsWith("You are Boss.") && user.contains("Review round")) {
                    int round = reviews.incrementAndGet();
                    assertTrue(user.contains("\"verdict\"") || user.contains("verdict"), "the step's own schema is requested");
                    content = round >= doneOnRound
                            ? "```json\n{\"verdict\": \"DONE\", \"fix\": \"\"}\n```"
                            : "```json\n{\"verdict\": \"AGAIN\", \"fix\": \"tighten part " + round + "\"}\n```";
                } else if (system.startsWith("You are Boss.")) {
                    content = "```json\n{\"plan\": \"do it\"}\n```";
                } else {
                    content = "```json\n{\"thought\": \"ok\", \"final_answer\": \"done\"}\n```";
                }
                return LLMResponse.builder().content(content).model(model).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.empty();
            }
        };
    }

    private static HarnessExecutor run(LLMClientFactory factory) {
        LoomScript script = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), factory);
        executor.initialize();
        executor.executeWorkflow("Main", Map.of("goal", "ship"));
        return executor;
    }

    @Test
    void loopStopsWhenTheConditionHoldsAndUsesTheStepSchema() {
        List<String> tasks = new CopyOnWriteArrayList<>();
        HarnessExecutor executor = run(factory(2, tasks, new AtomicInteger()));
        Map<String, Object> vars = executor.getContext().getAll();
        assertEquals("DONE", ((Map<?, ?>) vars.get("review")).get("verdict"));
        assertEquals("do it", ((Map<?, ?>) vars.get("plan")).get("plan"), "the agent's own schema still applies elsewhere");
        assertTrue(tasks.stream().anyMatch(t -> t.contains("Fix: tighten part 1")));
        assertTrue(tasks.stream().anyMatch(t -> t.contains("Review round 2")));
        assertFalse(vars.containsKey("escalation"), "on_exhausted only runs when the bound is hit");
    }

    @Test
    void boundedLoopRunsOnExhaustedInsteadOfSpinningForever() {
        List<String> tasks = new CopyOnWriteArrayList<>();
        AtomicInteger reviews = new AtomicInteger();
        HarnessExecutor executor = run(factory(Integer.MAX_VALUE, tasks, reviews));
        assertEquals(3, reviews.get(), "exactly max rounds of review, then stop");
        assertTrue(tasks.stream().anyMatch(t -> t.contains("Escalate after 3 rounds")));
        assertTrue(executor.getContext().getAll().containsKey("escalation"));
    }

    @Test
    void onExhaustedRequiresABound() {
        String bad = """
                workflow W() {
                    loop until (x == "y") {
                        note "a"
                    } on_exhausted {
                        note "b"
                    }
                }
                """;
        assertThrows(RuntimeException.class, () -> new LoomParser(new Lexer(bad).tokenize()).parseScript());
    }
}
