package io.github.llm4j.engram.core;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.runtime.VariableContext;
import io.github.llm4j.loom.runtime.DefaultVariableContext;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EngramEngineTest {

    @Test
    void testContextAssemblyAndDecay() {
        EngramEngine engine = new EngramEngine();
        AgentDef agent = new AgentDef("TestAgent");
        VariableContext ctx = new DefaultVariableContext();

        // 1. Initially, no context
        String briefing1 = engine.assembleContext(agent, "Write a sorting function", ctx);
        assertFalse(briefing1.contains("Context Briefing"));

        // 2. Store an outcome
        AgentResult result = AgentResult.builder().finalAnswer("I used QuickSort.").completed(true).iterations(1).build();
        engine.storeOutcome(agent, "Write a sorting function", result, ctx);

        // 3. Recall for a similar task
        String briefing2 = engine.assembleContext(agent, "Optimize the sorting algorithm", ctx);
        assertTrue(briefing2.contains("Context Briefing (Synthesized from relevant memory)"));
        assertTrue(briefing2.contains("I used QuickSort."));

        // 4. Recall for a completely unrelated task
        String briefing3 = engine.assembleContext(agent, "Bake a cake", ctx);
        assertTrue(briefing3.contains("Bake a cake"));
    }

    @Test
    void testPersistence() throws IOException {
        File tempFile = File.createTempFile("engram-store", ".json");
        tempFile.deleteOnExit();

        EngramEngine engine1 = new EngramEngine(tempFile.getAbsolutePath());
        AgentDef agent = new AgentDef("PersistentAgent");
        VariableContext ctx = new DefaultVariableContext();

        AgentResult result = AgentResult.builder().finalAnswer("The secret code is 42.").completed(true).iterations(1).build();
        engine1.storeOutcome(agent, "What is the secret?", result, ctx);

        // Create a new engine instance pointing to the same file
        EngramEngine engine2 = new EngramEngine(tempFile.getAbsolutePath());
        String briefing = engine2.assembleContext(agent, "Tell me the secret code", ctx);
        
        assertTrue(briefing.contains("The secret code is 42."));
    }
}
