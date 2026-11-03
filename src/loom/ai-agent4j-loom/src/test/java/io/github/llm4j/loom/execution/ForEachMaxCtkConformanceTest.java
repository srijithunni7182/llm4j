package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Runs the canonical bounded fan-out scenario of the Loom Conformance Test Kit ({@code loom/ctk})
 * through this runtime: a plan within the bound runs every item; a plan over it fails before any
 * item starts and says who wrote the list, with a trace event that holds counts and no item.
 */
class ForEachMaxCtkConformanceTest {

    private static final Path CTK = Path.of("..", "ctk").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final class Outcome {
        boolean failed;
        int workerCalls;
        TraceEvent fanout;
    }

    private static Outcome run(String goal, JsonNode plan) throws Exception {
        LoomScript script =
                new LoomParser(new Lexer(Files.readString(CTK.resolve("scripts/for_each_max.loom"))).tokenize())
                        .parseScript();
        AtomicInteger workers = new AtomicInteger();
        LLMClient model =
                new LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        StringBuilder all = new StringBuilder();
                        request.getMessages().forEach(m -> all.append(m.getContent()));
                        String reply;
                        if (all.toString().contains("You plan.")) reply = plan.toString();
                        else {
                            workers.incrementAndGet();
                            reply = "done";
                        }
                        return LLMResponse.builder().content(reply).model("m").build();
                    }

                    @Override
                    public Stream<LLMResponse> chatStream(LLMRequest request) {
                        return Stream.empty();
                    }
                };
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), name -> model);
        List<TraceEvent> events = new ArrayList<>();
        executor.addTraceListener(events::add);
        executor.initialize();
        Outcome out = new Outcome();
        try {
            executor.executeWorkflow("main", new HashMap<>(Map.of("goal", goal)));
        } catch (RuntimeException e) {
            out.failed = true;
        }
        out.workerCalls = workers.get();
        out.fanout = events.stream().filter(e -> e.type().equals(TraceEvent.FANOUT_EXCEEDED)).findFirst().orElse(null);
        return out;
    }

    @Test
    void theOutcomeMatchesTheCanonicalOneForAPlanWithinAndOverTheBound() throws Exception {
        JsonNode mock = JSON.readTree(Files.readString(CTK.resolve("mocks/for_each_max.json")));
        JsonNode expected = JSON.readTree(Files.readString(CTK.resolve("traces/for_each_max.json"))).get("cases");

        for (String name : List.of("within", "over")) {
            Outcome o = run(mock.get(name).get("goal").asText(), mock.get(name).get("plan"));
            JsonNode want = expected.get(name);
            assertEquals("failed".equals(want.get("outcome").asText()), o.failed, name);
            assertEquals(want.get("workerCalls").asInt(), o.workerCalls, name);
            if (want.has("failureEvent")) {
                JsonNode ev = want.get("failureEvent");
                assertTrue(o.fanout != null, name + ": fanout_exceeded was traced");
                assertEquals(ev.get("count").asInt(), ((Number) o.fanout.data().get("count")).intValue());
                assertEquals(ev.get("max").asInt(), ((Number) o.fanout.data().get("max")).intValue());
                assertEquals(ev.get("producer").asText(), o.fanout.data().get("producer"));
            } else {
                assertEquals(null, o.fanout, name);
            }
        }
    }
}
