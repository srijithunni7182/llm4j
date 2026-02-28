package io.github.llm4j.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.schedule.AgentScheduler;
import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.stream.Stream;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScheduledActionToolTest {

    private AgentScheduler scheduler;
    private ScheduledActionTool tool;

    @BeforeEach
    void setup() {
        // We just need a dummy agent to pass to the scheduler
        LLMClient dummyClient = new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) { return null; }
            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) { return Stream.empty(); }
        };
        ReActAgent dummyAgent = ReActAgent.builder().llmClient(dummyClient).build();
        scheduler = new AgentScheduler(dummyAgent, 1);
        tool = new ScheduledActionTool(scheduler);
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void testExecute_ValidOneTimeTask() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("instructions", "Check server health");
        args.put("delaySeconds", 2);

        String result = tool.execute(args);
        assertTrue(result.contains("Successfully scheduled a single background task"));
        assertTrue(result.contains("2 seconds"));
    }

    @Test
    void testExecute_ValidRecurringTask() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("instructions", "Ping database");
        args.put("delaySeconds", 5);
        args.put("isRecurring", true);

        String result = tool.execute(args);
        assertTrue(result.contains("Successfully scheduled a recurring background task"));
        assertTrue(result.contains("5 seconds"));
    }

    @Test
    void testExecute_MissingInstructions() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("delaySeconds", 5);

        String result = tool.execute(args);
        assertTrue(result.contains("Error: instructions missing"));
    }

    @Test
    void testExecute_InvalidDelay() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("instructions", "Check server health");
        args.put("delaySeconds", -1);

        String result = tool.execute(args);
        assertTrue(result.contains("Error: delaySeconds must be a positive integer"));
    }
}
