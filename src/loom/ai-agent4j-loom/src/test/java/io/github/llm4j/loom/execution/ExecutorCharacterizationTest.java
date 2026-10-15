package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins behaviour of the executor that other tests did not reach (found by a coverage run), before the executor was split into collaborators:
 * a handoff that runs its agent, a failure inside a timed step, and a failed step replayed from the journal. These describe what the executor does,
 * not what it ideally should do.
 */
class ExecutorCharacterizationTest {

    private static final String AGENTS = """
            agent Writer { model: "test" system: "You are Writer." }
            agent Closer { model: "test" system: "You are Closer." }
            """;

    /** Answers as the agent named in the system prompt; the agents in {@code failing} throw. */
    private static LLMClientFactory model(List<String> calls, List<String> failing) {
        return name -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                String system = request.getMessages().get(0).getContent();
                String agent = system.replaceFirst("(?s)^You are (\\w+)\\..*", "$1");
                calls.add(agent);
                if (failing.contains(agent)) throw new IllegalStateException("model down for " + agent);
                return LLMResponse.builder().content("```json\n{\"thought\": \"ok\", \"final_answer\": \"" + agent + " did it\"}\n```").model(name).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.empty();
            }
        };
    }

    private static HarnessExecutor executor(String body, LLMClientFactory factory, RunJournal journal) {
        LoomScript parsed = new LoomParser(new Lexer(AGENTS + body).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(parsed, new ToolRegistry(), factory);
        if (journal != null) executor.setJournal(journal);
        executor.initialize();
        return executor;
    }

    @Test
    void aHandoffRunsItsAgentJournalsItAndEndsTheWorkflow() {
        List<String> calls = new CopyOnWriteArrayList<>();
        RunJournal journal = RunJournal.inMemory();
        String script = """
                workflow Main() {
                    delegate "first" to Writer -> before
                    handoff "wrap up" to Closer
                    delegate "never" to Writer -> after
                }
                """;

        HarnessExecutor run = executor(script, model(calls, List.of()), journal);
        run.executeWorkflow("Main", Map.of());

        assertEquals(List.of("Writer", "Closer"), calls, "the handoff ran its agent, and nothing came after it");
        assertEquals("Writer did it", run.getContext().getVariable("before"));
        assertEquals("", run.getContext().getVariable("after"), "a variable that was never set reads as empty");
        assertTrue(journal.all().values().stream().anyMatch(e -> "handoff".equals(e.kind()) && "Closer did it".equals(e.value())));

        // the same journal again: the handoff is replayed, not run twice
        List<String> again = new CopyOnWriteArrayList<>();
        executor(script, model(again, List.of()), journal).executeWorkflow("Main", Map.of());
        assertEquals(List.of(), again, "every step, the handoff included, came from the journal");
    }

    @Test
    void anAgentThatFailsInsideATimedStepFailsTheStepWithItsOwnError() {
        List<String> calls = new CopyOnWriteArrayList<>();
        String script = """
                workflow Main() {
                    delegate "work" to Writer -> out_text timeout 5s on_failure {
                        note "handled"
                    }
                    delegate "after" to Closer -> done_text
                }
                """;

        HarnessExecutor run = executor(script, model(calls, List.of("Writer")), null);
        run.executeWorkflow("Main", Map.of());

        assertEquals(List.of("Writer", "Closer"), calls, "the failed step was handled and the run carried on");
        assertEquals("Closer did it", run.getContext().getVariable("done_text"));
        assertEquals("", run.getContext().getVariable("out_text"), "the failed step set nothing");
    }

    @Test
    void aFailedStepThatWasHandledIsReplayedAsFailedNotRunAgain() {
        RunJournal journal = RunJournal.inMemory();
        AtomicInteger handled = new AtomicInteger();
        String script = """
                workflow Main() {
                    delegate "work" to Writer -> out_text on_failure {
                        note "handled"
                    }
                    delegate "after" to Closer -> done_text
                }
                """;
        List<String> first = new CopyOnWriteArrayList<>();
        executor(script, model(first, List.of("Writer")), journal).executeWorkflow("Main", Map.of());
        assertEquals(List.of("Writer", "Closer"), first);
        assertTrue(journal.all().values().stream().anyMatch(e -> "failed".equals(e.kind())), "the handled failure is on the journal");

        List<String> second = new CopyOnWriteArrayList<>();
        HarnessExecutor resumed = executor(script, model(second, List.of("Writer")), journal);
        resumed.executeWorkflow("Main", Map.of());

        assertEquals(List.of(), second, "neither the failed step nor the finished one called a model again");
        assertEquals("Closer did it", resumed.getContext().getVariable("done_text"));
    }
}
