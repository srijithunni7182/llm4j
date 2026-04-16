package io.github.llm4j.loom.execution;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

public class RoutingIntegrationTest {

    @Test
    public void testRoutingLogic() throws Exception {
        String scriptContent = new String(Files.readAllBytes(Paths.get("src/test/resources/routing-test.loom")));
        
        Lexer lexer = new Lexer(scriptContent);
        LoomParser parser = new LoomParser(lexer.tokenize());
        LoomScript script = parser.parseScript();

        LLMClientFactory mockFactory = modelName -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                return LLMResponse.builder()
                        .content("MOCKED_AI_" + request.getMessages().get(request.getMessages().size()-1).getContent())
                        .build();
            }
            @Override
            public java.util.stream.Stream<LLMResponse> chatStream(LLMRequest request) {
                return java.util.stream.Stream.of(chat(request));
            }
        };

        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), mockFactory);
        
        // Mock Human Interface for the loop
        executor.setHumanInterface(new HumanInterface() {
            private int callCount = 0;
            @Override
            public String promptHuman(String message) {
                callCount++;
                if (callCount == 1) return "false";
                return "true";
            }
        });

        executor.initialize();

        HarnessContext ctx = executor.getContext();
        ctx.setVariable("data", "hello");
        ctx.setVariable("ready", "true");
        ctx.setVariable("done", "false");

        executor.executeWorkflow("RoutingTest", new HashMap<>());

        // Verify broadcast combined results
        String analysisResults = ctx.getVariable("analysis_results");
        assertNotNull(analysisResults);
        assertTrue(analysisResults.contains("Analyze this: hello"), "Should contain mocked broadcast result: " + analysisResults);
        
        // Verify Alt (if branch was taken)
        assertNotNull(ctx.getVariable("a1"));
        assertEquals("", ctx.getVariable("a2")); // a2 was in else branch => empty

        // Verify Loop (exited after context was set to true)
        assertEquals("true", ctx.getVariable("done"));
    }
}
