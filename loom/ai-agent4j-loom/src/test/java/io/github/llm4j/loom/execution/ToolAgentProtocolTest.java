package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Tool-using agents must see their tools and the ReAct protocol; output_schema fields must be
 * addressable from alt conditions with dotted paths.
 */
class ToolAgentProtocolTest {

    private static final String SCRIPT = """
            agent Scout {
                model: "test"
                system: "You are Scout."
                tools: [MockTool]
            }

            agent Critic {
                model: "test"
                system: "You are Critic."
                output_schema: {
                    score: number,
                    verdict: enum["SHIP", "REVISE"]
                }
            }

            workflow Main(idea) {
                delegate "Research {idea}" to Scout -> research
                delegate "Judge {research}" to Critic -> review
                alt (review.verdict == "SHIP") {
                    note "shipping"
                    delegate "Celebrate" to Scout -> party
                }
            }
            """;

    @Test
    void toolAgentsGetProtocolAndDottedConditionsResolve() {
        List<String> systemPrompts = new CopyOnWriteArrayList<>();
        LLMClientFactory factory = model -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                String system = request.getMessages().get(0).getContent();
                systemPrompts.add(system);
                String content = system.startsWith("You are Critic.")
                        ? "```json\n{\"score\": 9, \"verdict\": \"SHIP\"}\n```"
                        : "```json\n{\"thought\": \"done\", \"final_answer\": \"ok\"}\n```";
                return LLMResponse.builder().content(content).model(model).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.empty();
            }
        };

        LoomScript script = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
        ToolRegistry registry = new ToolRegistry();
        registry.register("MockTool", new MockTool());

        List<String> customized = new CopyOnWriteArrayList<>();
        HarnessExecutor executor = new HarnessExecutor(script, registry, factory) {
            @Override
            protected void customizeAgent(AgentDef agentDef, ReActAgent.Builder builder) {
                customized.add(agentDef.getName());
            }
        };
        executor.initialize();
        executor.executeWorkflow("Main", Map.of("idea", "gym"));

        assertEquals(List.of("Scout", "Critic"), customized);

        String scoutPrompt = systemPrompts.stream().filter(p -> p.startsWith("You are Scout.")).findFirst().orElseThrow();
        assertTrue(scoutPrompt.contains("MockTool"), "tool agent should see its tools");
        assertTrue(scoutPrompt.contains("action_input"), "tool agent should see the ReAct protocol");

        String criticPrompt = systemPrompts.stream().filter(p -> p.startsWith("You are Critic.")).findFirst().orElseThrow();
        assertEquals("You are Critic.", criticPrompt, "tool-less agents keep their verbatim prompt");

        Object review = executor.getContext().getVariable("review");
        assertInstanceOf(Map.class, review);
        assertEquals("SHIP", ((Map<?, ?>) review).get("verdict"));
        assertEquals("ok", executor.getContext().getVariable("party"), "dotted alt condition should pass");
    }
}
