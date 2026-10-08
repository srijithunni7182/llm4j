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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** for each / parallel for each with run-time targets, and per-step timeout + backoff. */
class ForEachAndResilienceTest {

    private static LLMClientFactory model(List<String> calls, Map<String, Long> sleepMs) {
        return name -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                String system = request.getMessages().get(0).getContent();
                String user = request.getMessages().get(request.getMessages().size() - 1).getContent();
                String agent = system.replaceFirst("(?s)^You are (\\w+)\\..*", "$1");
                calls.add(agent + "|" + Thread.currentThread().getName() + "|" + user);
                long sleep = sleepMs.getOrDefault(agent, 0L);
                if (sleep > 0) {
                    try { Thread.sleep(sleep); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                String content = agent.equals("Lead")
                        ? "```json\n{\"fixes\": [{\"owner\": \"Writer\", \"output\": \"copy\", \"note\": \"shorter\"},"
                          + " {\"owner\": \"Painter\", \"output\": \"art\", \"note\": \"brighter\"}]}\n```"
                        : "```json\n{\"thought\": \"ok\", \"final_answer\": \"" + agent + " did it\"}\n```";
                return LLMResponse.builder().content(content).model(name).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.empty();
            }
        };
    }

    private static HarnessExecutor run(String script, LLMClientFactory factory, Map<String, String> inputs) {
        LoomScript parsed = new LoomParser(new Lexer(script).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(parsed, new ToolRegistry(), factory);
        executor.initialize();
        executor.executeWorkflow("Main", inputs);
        return executor;
    }

    private static final String AGENTS = """
            agent Lead {
                model: "test"
                system: "You are Lead."
                output_schema: { fixes: list<{ owner: string, output: string, note: string }> }
            }
            agent Writer { model: "test" system: "You are Writer." }
            agent Painter { model: "test" system: "You are Painter." }
            """;

    @Test
    void forEachSendsEachItemToTheAgentItNames() {
        List<String> calls = new CopyOnWriteArrayList<>();
        HarnessExecutor executor = run(AGENTS + """
                workflow Main() {
                    delegate "Review" to Lead -> review
                    for each fix in review.fixes {
                        delegate "Fix it: {fix.note} (item {_index})" to {fix.owner} -> {fix.output}
                    }
                }
                """, model(calls, Map.of()), Map.of());

        assertEquals("Writer did it", executor.getContext().getVariable("copy"));
        assertEquals("Painter did it", executor.getContext().getVariable("art"));
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("Writer|") && c.contains("Fix it: shorter (item 0)")));
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("Painter|") && c.contains("Fix it: brighter (item 1)")));
        assertFalse(executor.getContext().getAll().containsKey("fix"), "the item is block-local, not a workflow variable");
    }

    @Test
    void parallelForEachFansOutConcurrently() {
        List<String> calls = new CopyOnWriteArrayList<>();
        long start = System.nanoTime();
        HarnessExecutor executor = run(AGENTS + """
                workflow Main() {
                    delegate "Review" to Lead -> review
                    parallel for each fix in review.fixes {
                        delegate "Fix it: {fix.note}" to {fix.owner} -> {fix.output}
                    }
                }
                """, model(calls, Map.of("Writer", 400L, "Painter", 400L)), Map.of());
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertEquals("Writer did it", executor.getContext().getVariable("copy"));
        assertEquals("Painter did it", executor.getContext().getVariable("art"));
        Set<String> threads = ConcurrentHashMap.newKeySet();
        calls.stream().filter(c -> !c.startsWith("Lead|")).forEach(c -> threads.add(c.split("\\|")[1]));
        assertEquals(2, threads.size(), "each item ran on its own thread");
        assertTrue(ms < 780, "two 400 ms items finished together, not one after the other: " + ms + " ms");
    }

    @Test
    void aStepThatHangsTimesOutRetriesWithBackoffThenFallsBack() {
        List<String> calls = new CopyOnWriteArrayList<>();
        AtomicInteger dummy = new AtomicInteger();
        long start = System.nanoTime();
        HarnessExecutor executor = run(AGENTS + """
                workflow Main() {
                    delegate "Write" to Writer -> copy retry 1 backoff 200ms timeout 150ms on_failure {
                        delegate "Rescue after: {_error}" to Painter -> rescue
                    }
                }
                """, model(calls, Map.of("Writer", 2_000L)), Map.of());
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertEquals(2, calls.stream().filter(c -> c.startsWith("Writer|")).count(), "one try + one retry");
        assertTrue(ms >= 150 + 200 + 150, "timeout, backoff, timeout: " + ms + " ms");
        assertTrue(ms < 1_900, "never waited for the hung model call: " + ms + " ms");
        String rescue = calls.stream().filter(c -> c.startsWith("Painter|")).findFirst().orElseThrow();
        assertTrue(rescue.contains("Rescue after: Step timed out after 150 ms"), rescue);
        assertEquals("Painter did it", executor.getContext().getVariable("rescue"));
        assertFalse(executor.getContext().getAll().containsKey("_error"));
        assertEquals(0, dummy.get());
    }
}
