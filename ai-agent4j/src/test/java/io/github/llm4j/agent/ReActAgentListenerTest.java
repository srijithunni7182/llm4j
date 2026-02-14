package io.github.llm4j.agent;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ReActAgentListenerTest {

    @Mock
    private LLMClient llmClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testListeners() {
        // Mock LLM sequence: Thought -> Action -> Thought -> Final Answer
        when(llmClient.chat(any(LLMRequest.class)))
                .thenReturn(
                        LLMResponse.builder().content(
                                "Thought: I need to check something.\nAction: echo\nAction Input: {\"input\":\"hello\"}\n")
                                .build(),
                        LLMResponse.builder().content("Thought: I got it.\nFinal Answer: Done").build());

        List<String> events = new ArrayList<>();
        AgentEventListener listener = new AgentEventListener() {
            @Override
            public void onThought(String thought) {
                events.add("THOUGHT: " + thought);
            }

            @Override
            public void onAction(String toolName, String toolInput) {
                events.add("ACTION: " + toolName);
            }

            @Override
            public void onObservation(String observation) {
                events.add("OBSERVATION: " + observation);
            }
        };

        io.github.llm4j.agent.tools.EchoTool echoTool = new io.github.llm4j.agent.tools.EchoTool();

        ReActAgent agent = ReActAgent.builder()
                .llmClient(llmClient)
                .addTool(echoTool)
                .addListener(listener)
                .build();

        agent.run("Test");

        // Verify event sequence
        assertTrue(events.contains("THOUGHT: I need to check something."));
        assertTrue(events.contains("ACTION: echo"));
        // Observation comes from tool execution
        assertTrue(events.stream().anyMatch(e -> e.startsWith("OBSERVATION: hello")));
        assertTrue(events.contains("THOUGHT: I got it."));
    }
}
