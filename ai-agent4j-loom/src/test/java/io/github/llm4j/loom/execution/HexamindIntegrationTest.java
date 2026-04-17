package io.github.llm4j.loom.execution;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.*;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class HexamindIntegrationTest {

    @Test
    public void testHexamindWorkflow() throws Exception {
        // Read script
        String scriptContent = new String(Files.readAllBytes(Paths.get("src/test/resources/hexamind-debate.loom")));
        
        // Phase 1 & 2: Tokenize and Parse
        Lexer lexer = new Lexer(scriptContent);
        LoomParser parser = new LoomParser(lexer.tokenize());
        LoomScript script = parser.parseScript();

        // Ensure parser extracted 3 agents and 1 workflow
        assertEquals(3, script.getAgents().size());
        assertEquals(1, script.getWorkflows().size());

        // Mock LLM Client Factory
        LLMClientFactory mockFactory = modelName -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                // Simple mock that returns an acknowledgment based on the prompt
                return LLMResponse.builder()
                        .content("MOCK_RESPONSE_TO: " + request.getMessages().get(request.getMessages().size()-1).getContent())
                        .build();
            }

            @Override
            public java.util.stream.Stream<LLMResponse> chatStream(LLMRequest request) {
                return java.util.stream.Stream.of(chat(request));
            }
        };

        ToolRegistry registry = new ToolRegistry(); // empty for now

        // Phase 3: Execute
        HarnessExecutor executor = new HarnessExecutor(script, registry, mockFactory);
        executor.initialize();

        Map<String, String> inputs = new HashMap<>();
        inputs.put("problem", "Should AI be open source?");
        
        // Run workflow
        executor.executeWorkflow("Collaborate", inputs);

        // Verify state changes inside context
        VariableContext context = executor.getContext();
        
        String analystAnalysis = context.getVariable("analyst_analysis");
        assertNotNull(analystAnalysis);
        assertTrue(analystAnalysis.contains("Should AI be open source?")); // Because 'problem' interpolation
        
        String consensus = context.getVariable("consensus");
        assertNotNull(consensus);
        assertTrue(consensus.contains("Synthesize the following"));
    }
}
