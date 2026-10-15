package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Two hazards found while reading the executor, written as tests first and seen to fail:
 * a step that timed out must stop its agent (it used to keep calling the model), and an agent that was cancelled must never be
 * recorded as having succeeded.
 */
class InterventionSafetyTest {

    private static final String ACTION = "```json\n{\"thought\": \"look\", \"action\": \"MockTool\", \"action_input\": {\"test\": \"x\"}}\n```";
    private static final String DONE = "```json\n{\"thought\": \"ok\", \"final_answer\": \"done\"}\n```";

    private static HarnessExecutor executor(String script, LLMClientFactory factory, RunJournal journal) {
        ToolRegistry tools = new ToolRegistry();
        tools.register("MockTool", new MockTool());
        LoomScript parsed = new LoomParser(new Lexer(script).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(parsed, tools, factory);
        if (journal != null) executor.setJournal(journal);
        executor.initialize();
        return executor;
    }

    /** Answers with a tool action every time, so the agent keeps looping until something stops it; the named agent's calls take {@code delayMs} each. */
    private static LLMClientFactory looping(AtomicInteger calls, long delayMs, CountDownLatch entered, CountDownLatch release) {
        return name -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                String agent = request.getMessages().get(0).getContent().replaceFirst("(?s)^You are (\\w+)\\..*", "$1");
                if (!agent.equals("Looper")) return LLMResponse.builder().content(DONE).model(name).build();
                int n = calls.incrementAndGet();
                try {
                    if (entered != null && n == 1) {
                        entered.countDown();
                        if (release != null) release.await(10, TimeUnit.SECONDS);
                    } else if (delayMs > 0) {
                        Thread.sleep(delayMs);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return LLMResponse.builder().content(ACTION).model(name).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.empty();
            }
        };
    }

    @Test
    void aStepThatTimesOutStopsItsAgentInsteadOfLeavingItRunning() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HarnessExecutor run = executor("""
                agent Looper { model: "test" system: "You are Looper." tools: [MockTool] max_iterations: 200 }
                workflow Main() {
                    delegate "go" to Looper -> out_text timeout 300ms on_failure { note "timed out" }
                }
                """, looping(calls, 100, null, null), null);

        run.executeWorkflow("Main", Map.of());
        int atTimeout = calls.get();
        assertTrue(atTimeout >= 1, "the agent was running when the step timed out");
        Thread.sleep(700);

        assertTrue(calls.get() - atTimeout <= 1, "the agent kept calling the model after its step timed out: " + atTimeout + " -> " + calls.get());
    }

    @Test
    void anAgentThatWasCancelledIsNeverRecordedAsHavingSucceeded() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RunJournal journal = RunJournal.inMemory();
        HarnessExecutor run = executor("""
                agent Looper { model: "test" system: "You are Looper." tools: [MockTool] max_iterations: 50 }
                agent Closer { model: "test" system: "You are Closer." }
                workflow Main() {
                    delegate "go" to Looper -> out_text retry 2 on_failure { note "handled" }
                    delegate "after" to Closer -> done_text
                }
                """, looping(calls, 0, entered, release), journal);

        Thread runner = new Thread(() -> run.executeWorkflow("Main", Map.of()));
        runner.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the agent started");
        assertEquals(1, run.cancel("Looper", "operator stop", "test", StopOutcome.SKIP));
        release.countDown();
        runner.join(10_000);

        assertFalse(runner.isAlive(), "the run ended");
        assertTrue(calls.get() <= 2, "a cancelled step is not retried: " + calls.get() + " calls");
        assertEquals("", run.getContext().getVariable("out_text"), "the cancelled agent's output was not taken as the step's result");
        assertEquals("done", run.getContext().getVariable("done_text"), "the run carried on after the skipped step");
        assertTrue(journal.all().values().stream().noneMatch(e -> "delegate".equals(e.kind()) && String.valueOf(e.value()).contains("cancel")),
                "no success entry holds the cancelled agent's text");
        assertTrue(journal.all().values().stream().anyMatch(e -> "skipped".equals(e.kind()) && String.valueOf(e.value()).contains("operator stop")),
                "the step is recorded as skipped, with the reason");
    }
}
