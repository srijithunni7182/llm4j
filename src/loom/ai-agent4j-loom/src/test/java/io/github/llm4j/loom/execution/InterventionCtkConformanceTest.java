package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunStopped;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs the intervention scenarios of the Loom Conformance Test Kit ({@code loom/ctk}) through this runtime and holds its trace to the canonical one.
 *
 * <p>Interventions are scripted, not timed: a scenario's fixture ({@code interventions/NAME.json}) says "after call N of agent A, issue this
 * steer, cancel or wrap-up", and the scripted model issues it as it returns that call. So every run is the same run. See the kit's README.
 */
class InterventionCtkConformanceTest {

    private static final Path CTK = Path.of("..", "ctk").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();

    static List<String> scenarios() throws Exception {
        try (Stream<Path> files = Files.list(CTK.resolve("interventions"))) {
            return files.map(p -> p.getFileName().toString().replace(".json", "")).sorted().toList();
        }
    }

    /** A step of the trace the kit compares: the fields it checks. */
    private static Map<String, String> step(String kind, String agent, String payload, String variable) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("agentName", agent);
        m.put("payload", payload);
        m.put("outputVariable", variable);
        return m;
    }

    private static String canonical(Map<String, Object> m) throws Exception {
        return JSON.writeValueAsString(new TreeMap<>(m));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void theTraceMatchesTheCanonicalOne(String name) throws Exception {
        JsonNode fixture = JSON.readTree(Files.readString(CTK.resolve("interventions/" + name + ".json")));
        JsonNode expect = fixture.get("expect");
        String source = Files.readString(CTK.resolve("scripts/" + name + ".loom"));

        AtomicReference<HarnessExecutor> executor = new AtomicReference<>();
        Map<String, AtomicInteger> calls = new HashMap<>();
        Map<String, List<String>> seen = new HashMap<>();
        List<String> answers = new ArrayList<>();
        fixture.path("human").forEach(a -> answers.add(a.asText()));
        AtomicInteger asked = new AtomicInteger();
        String tool = fixture.get("tool").asText();

        LLMClientFactory models = modelName -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                String system = request.getMessages().get(0).getContent();
                String agent = system.replaceFirst("(?s)^You are (\\w+)\\..*", "$1");
                String all = request.getMessages().stream().map(m -> m.getContent()).reduce("", (a, b) -> a + "\n" + b);
                seen.computeIfAbsent(agent, k -> Collections.synchronizedList(new ArrayList<>())).add(all);
                if (all.contains("Stop using tools now")) {
                    return reply(fixture.path("wrapUp").path(agent).asText("wrapped"), false, tool);
                }
                int n = calls.computeIfAbsent(agent, k -> new AtomicInteger()).incrementAndGet();
                JsonNode replies = fixture.path("replies").path(agent);
                String scripted = n <= replies.size() ? replies.get(n - 1).asText() : "final:ok";
                LLMResponse out = reply(scripted, scripted.equals("act"), tool);
                for (JsonNode i : fixture.path("interventions")) { // issued as the call returns, so the run is the same every time
                    if (i.get("agent").asText().equals(agent) && i.get("atCall").asInt() == n) issue(executor.get(), i);
                }
                return out;
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };

        ToolRegistry tools = new ToolRegistry();
        tools.register("MockTool", new MockTool());
        HarnessExecutor ex = new HarnessExecutor(new LoomParser(new Lexer(source).tokenize()).parseScript(), tools, models);
        executor.set(ex);
        RunJournal journal = RunJournal.inMemory();
        ex.setJournal(journal);
        ex.setHumanInterface(new HumanInterface() {
            @Override
            public String promptHuman(String message) {
                return answers.isEmpty() ? "skip" : answers.get(Math.min(asked.getAndIncrement(), answers.size() - 1));
            }
        });
        List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
        ex.addTraceListener(events::add);

        if (expect.has("loadError")) {
            Exception refused = assertThrows(Exception.class, ex::initialize);
            assertTrue(refused.getMessage().contains(expect.get("loadError").asText()), refused.getMessage());
            return;
        }
        ex.initialize();
        String ended = "completed";
        try {
            ex.executeWorkflow("main", new HashMap<>());
        } catch (RunSuspended suspended) {
            ended = "suspended";
        } catch (RunStopped stopped) {
            ended = "stopped";
        }

        // the trace
        List<Map<String, String>> actual = new ArrayList<>();
        for (TraceEvent e : events) {
            switch (e.type()) {
                case TraceEvent.DELEGATE_START -> actual.add(step("delegate", e.agent(), null, String.valueOf(e.data().get("variable"))));
                case TraceEvent.STEP_STOPPED -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("kind", String.valueOf(e.data().get("kind")).toLowerCase());
                    p.put("outcome", e.data().get("outcome"));
                    p.put("reason", e.data().get("category"));
                    actual.add(step("stop", e.agent(), canonical(p), null));
                }
                case TraceEvent.INTERVENTION -> {
                    if ("steer".equals(e.data().get("action"))) actual.add(step("steer", e.agent(), String.valueOf(e.data().get("text")), null));
                }
                case TraceEvent.REWIND -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("checkpoint", e.data().get("checkpoint"));
                    p.put("generation", e.data().get("generation"));
                    actual.add(step("rewind", null, canonical(p), null));
                }
                default -> { }
            }
        }
        JsonNode canonicalTrace = JSON.readTree(Files.readString(CTK.resolve("traces/" + name + ".json")));
        assertEquals("main", canonicalTrace.get("workflowName").asText());
        List<Map<String, String>> wanted = new ArrayList<>();
        for (JsonNode s : canonicalTrace.get("steps")) {
            wanted.add(step(s.get("kind").asText(), s.has("agentName") ? s.get("agentName").asText() : null,
                    s.has("payload") ? s.get("payload").asText() : null, s.has("outputVariable") ? s.get("outputVariable").asText() : null));
        }
        // a delegate step carries no payload in the kit's comparison (a model's input is not deterministic); the rest do
        actual.forEach(m -> { if ("delegate".equals(m.get("kind"))) m.put("payload", null); });
        wanted.forEach(m -> { if ("delegate".equals(m.get("kind"))) m.put("payload", null); });
        assertEquals(wanted, actual, name);

        // what else the scenario promises
        assertEquals(expect.get("run").asText(), ended, "how the run ended");
        assertEquals(expect.get("asked").asInt(), asked.get(), "how many times a person was asked");
        expect.path("variables").fields().forEachRemaining(v ->
                assertEquals(v.getValue().asText(), String.valueOf(ex.getContext().getVariable(v.getKey())), "variable " + v.getKey()));
        expect.path("modelSaw").fields().forEachRemaining(v ->
                assertTrue(seen.getOrDefault(v.getKey(), List.of()).stream().anyMatch(t -> t.contains(v.getValue().asText())), v.getKey() + " saw " + v.getValue().asText()));
        if (expect.has("journalNever")) {
            assertTrue(journal.all().values().stream().noneMatch(e -> String.valueOf(e.value()).contains(expect.get("journalNever").asText())),
                    "the journal never holds what a cancelled step had");
        }
    }

    private static LLMResponse reply(String scripted, boolean act, String tool) {
        String body;
        if (act) {
            String input = tool.equals("calculator") ? "{\"expression\": \"1+1\"}" : "{\"test\": \"x\"}";
            body = "{\"thought\": \"act\", \"action\": \"" + tool + "\", \"action_input\": " + input + "}";
        } else {
            String text = scripted.startsWith("final:") ? scripted.substring(6) : scripted;
            body = "{\"thought\": \"done\", \"final_answer\": \"" + text.replace("\"", "\\\"") + "\"}";
        }
        return LLMResponse.builder().content("```json\n" + body + "\n```").model("ctk").build();
    }

    private static void issue(HarnessExecutor ex, JsonNode i) {
        String agent = i.get("agent").asText();
        String origin = i.path("origin").asText("ctk");
        switch (i.get("kind").asText()) {
            case "steer" -> assertEquals(1, ex.steer(agent, i.get("note").asText(), origin), "the steer reached the agent");
            case "wrap_up" -> ex.wrapUp(agent, i.get("reason").asText(), origin);
            case "cancel" -> ex.cancel(agent, i.get("reason").asText(), origin, i.has("outcome") ? StopOutcome.valueOf(i.get("outcome").asText()) : null);
            case "cancel_back" -> assertEquals(i.path("refused").asBoolean(false) ? 0 : 1,
                    ex.cancelBack(agent, i.get("checkpoint").asText(), i.get("reason").asText(), origin), "the person's send-back " + (i.path("refused").asBoolean(false) ? "is refused" : "is taken"));
            default -> fail("unknown intervention kind " + i.get("kind"));
        }
    }

    @Test
    void everyScenarioHasAScriptAndATrace() throws Exception {
        List<String> all = scenarios();
        assertTrue(all.size() >= 17, "the kit has the intervention scenarios: " + all);
        for (String name : all) {
            assertTrue(Files.exists(CTK.resolve("scripts/" + name + ".loom")), name + " has a script");
            assertTrue(Files.exists(CTK.resolve("traces/" + name + ".json")), name + " has a trace");
        }
    }
}
