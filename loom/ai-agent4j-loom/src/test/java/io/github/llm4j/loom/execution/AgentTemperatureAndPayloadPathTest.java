package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Agents can declare their own sampling temperature; payloads can address fields of structured results. */
class AgentTemperatureAndPayloadPathTest {

    private static final String SCRIPT = """
            agent Dreamer {
                model: "test"
                system: "You are Dreamer."
                temperature: 1.2
                output_schema: {
                    lens: string,
                    ideas: list<string>,
                    meta: { style: string }
                }
            }

            agent Checker {
                model: "test"
                system: "You are Checker."
                temperature: 0.1
            }

            agent Plain {
                model: "test"
                system: "You are Plain."
            }

            workflow Main(topic) {
                delegate "Dream about {topic}" to Dreamer -> sheet
                delegate "Lens={sheet.lens} Style={sheet.meta.style} First={sheet.ideas.0} Missing={sheet.nope} Whole={topic}" to Checker -> check
                delegate "Plain task" to Plain -> plain
            }
            """;

    @Test
    void temperaturesAreAppliedAndDottedPayloadPathsResolve() {
        Map<String, Double> temperatures = new ConcurrentHashMap<>();
        Map<String, String> tasks = new ConcurrentHashMap<>();
        LLMClientFactory factory = model -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                String system = request.getMessages().get(0).getContent();
                String user = request.getMessages().get(request.getMessages().size() - 1).getContent();
                String agent = system.replaceFirst("(?s)^You are (\\w+)\\..*", "$1");
                temperatures.put(agent, request.getTemperature());
                tasks.put(agent, user);
                String content = agent.equals("Dreamer")
                        ? "```json\n{\"lens\": \"myth vs reality\", \"ideas\": [\"tides\", \"salt\"], \"meta\": {\"style\": \"risograph\"}}\n```"
                        : "```json\n{\"thought\": \"done\", \"final_answer\": \"ok\"}\n```";
                return LLMResponse.builder().content(content).model(model).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.empty();
            }
        };

        LoomScript script = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
        assertEquals(1.2, script.getAgents().get(0).getTemperature());
        assertNull(script.getAgents().get(2).getTemperature());

        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), factory);
        executor.initialize();
        executor.executeWorkflow("Main", Map.of("topic", "the sea"));

        assertEquals(1.2, temperatures.get("Dreamer"));
        assertEquals(0.1, temperatures.get("Checker"));
        assertNotNull(temperatures.get("Plain"), "agents without a temperature keep the runtime default");

        String task = tasks.get("Checker");
        assertTrue(task.contains("Lens=myth vs reality"), task);
        assertTrue(task.contains("Style=risograph"), task);
        assertTrue(task.contains("First=tides"), task);
        assertTrue(task.contains("Missing= Whole"), "missing fields read as empty, like in conditions: " + task);
        assertTrue(task.contains("Whole=the sea"), task);
    }

    @Test
    void temperatureOutOfRangeIsAParseError() {
        String bad = """
                agent Hot {
                    model: "test"
                    temperature: 3.5
                }
                """;
        assertThrows(RuntimeException.class, () -> new LoomParser(new Lexer(bad).tokenize()).parseScript());
    }
}
