package io.github.llm4j.agent.schedule;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.LLMClient;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentSchedulerTest {

    private AgentScheduler scheduler;
    private CountDownLatch latch;
    private String lastInvokedInstruction;

    @BeforeEach
    void setup() {
        latch = new CountDownLatch(1);
        
        // Use a real agent with a mock client to intercept the call
        LLMClient mockClient = new LLMClient() {
            @Override
            public io.github.llm4j.model.LLMResponse chat(io.github.llm4j.model.LLMRequest request) {
                lastInvokedInstruction = request.getMessages().get(1).getContent(); // User message
                latch.countDown();
                return io.github.llm4j.model.LLMResponse.builder().content("Done").build();
            }

            @Override
            public java.util.stream.Stream<io.github.llm4j.model.LLMResponse> chatStream(io.github.llm4j.model.LLMRequest request) {
                return java.util.stream.Stream.empty();
            }
        };

        ReActAgent agent = ReActAgent.builder().llmClient(mockClient).build();
        scheduler = new AgentScheduler(agent, 1);
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void testScheduleTask() throws InterruptedException {
        ScheduledFuture<?> future = scheduler.scheduleTask("Do the thing", Duration.ofMillis(10));
        assertNotNull(future);
        assertFalse(future.isDone());

        // Wait for the agent to be invoked
        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertTrue(completed, "Agent was not invoked by the scheduler in time");
        assertTrue(lastInvokedInstruction.contains("Do the thing"));
    }
}
