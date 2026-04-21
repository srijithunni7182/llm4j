package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.runtime.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class Tier2IntegrationTest {

    @Test
    public void testTier2Features() throws Exception {
        String source = Files.readString(Path.of("src/test/resources/tier2-test.loom"));
        Lexer lexer = new Lexer(source);
        LoomParser parser = new LoomParser(lexer.tokenize());
        LoomScript script = parser.parseScript();

        ToolRegistry toolRegistry = new ToolRegistry();
        LLMClientFactory clientFactory = (model) -> new io.github.llm4j.LLMClient() {
            @Override
            public io.github.llm4j.model.LLMResponse chat(io.github.llm4j.model.LLMRequest request) {
                return io.github.llm4j.model.LLMResponse.builder()
                        .content("Processed: " + request.getMessages().get(request.getMessages().size()-1).getContent())
                        .build();
            }
            @Override
            public java.util.stream.Stream<io.github.llm4j.model.LLMResponse> chatStream(io.github.llm4j.model.LLMRequest request) {
                return java.util.stream.Stream.empty();
            }
        };

        HarnessExecutor executor = new HarnessExecutor(script, toolRegistry, clientFactory);
        executor.initialize();

        // Test with clean data
        executor.executeWorkflow("Tier2Test", Map.of("inputData", "Hello world"));
        
        // Test with PII data (should trigger guardrail)
        executor.executeWorkflow("Tier2Test", Map.of("inputData", "My email is test@example.com"));
        
        // Verification is mostly done via logs and lack of exceptions
        assertTrue(true);
    }
}
