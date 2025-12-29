package io.github.llm4j.agent;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.tools.CalculatorTool;
import io.github.llm4j.agent.tools.EchoTool;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReActAgentTest {

    @Mock
    private LLMClient mockClient;

    private ReActAgent agent;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testAgentWithSingleToolCall() {
        // Mock LLM to return a thought, action, and then final answer
        when(mockClient.chat(any(LLMRequest.class)))
                .thenReturn(createResponse(
                        "Thought: I need to calculate 2 + 2\n" +
                                "Action: Calculator\n" +
                                "Action Input: 2 + 2"))
                .thenReturn(createResponse(
                        "Thought: I now know the final answer\n" +
                                "Final Answer: 4"));

        agent = ReActAgent.builder()
                .llmClient(mockClient)
                .addTool(new CalculatorTool())
                .maxIterations(5)
                .build();

        AgentResult result = agent.run("What is 2 + 2?");

        assertThat(result.isCompleted()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("4");
        assertThat(result.getSteps()).hasSize(1);
        assertThat(result.getIterations()).isEqualTo(2);

        AgentResult.AgentStep step = result.getSteps().get(0);
        assertThat(step.getAction()).isEqualToIgnoringCase("Calculator");
        assertThat(step.getActionInput()).isEqualTo("2 + 2");
        assertThat(step.getObservation()).isEqualTo("4");
    }

    @Test
    void testAgentWithMultipleToolCalls() {
        when(mockClient.chat(any(LLMRequest.class)))
                .thenReturn(createResponse(
                        "Thought: I need to calculate first number\n" +
                                "Action: Calculator\n" +
                                "Action Input: 5 * 3"))
                .thenReturn(createResponse(
                        "Thought: Now I need to add 10\n" +
                                "Action: Calculator\n" +
                                "Action Input: 15 + 10"))
                .thenReturn(createResponse(
                        "Thought: I now know the final answer\n" +
                                "Final Answer: The result is 25"));

        agent = ReActAgent.builder()
                .llmClient(mockClient)
                .addTool(new CalculatorTool())
                .maxIterations(10)
                .build();

        AgentResult result = agent.run("Calculate (5 * 3) + 10");

        assertThat(result.isCompleted()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("The result is 25");
        assertThat(result.getSteps()).hasSize(2);
    }

    @Test
    void testAgentWithUnknownTool() {
        when(mockClient.chat(any(LLMRequest.class)))
                .thenReturn(createResponse(
                        "Thought: I'll use the web search tool\n" +
                                "Action: WebSearch\n" +
                                "Action Input: current weather"))
                .thenReturn(createResponse(
                        "Thought: The tool doesn't exist, I cannot answer\n" +
                                "Final Answer: I don't have access to the required tools"));

        agent = ReActAgent.builder()
                .llmClient(mockClient)
                .addTool(new EchoTool())
                .maxIterations(5)
                .build();

        AgentResult result = agent.run("What's the weather?");

        assertThat(result.getSteps()).hasSize(1);
        AgentResult.AgentStep step = result.getSteps().get(0);
        assertThat(step.getObservation()).contains("Unknown tool");
        assertThat(step.getObservation()).contains("WebSearch");
    }

    @Test
    void testAgentMaxIterations() {
        // Always return action without final answer
        when(mockClient.chat(any(LLMRequest.class)))
                .thenReturn(createResponse(
                        "Thought: Let me think\n" +
                                "Action: Echo\n" +
                                "Action Input: test"));

        agent = ReActAgent.builder()
                .llmClient(mockClient)
                .addTool(new EchoTool())
                .maxIterations(3)
                .build();

        AgentResult result = agent.run("Test question");

        assertThat(result.isCompleted()).isFalse();
        assertThat(result.getIterations()).isEqualTo(3);
        assertThat(result.getFinalAnswer()).contains("Maximum iterations reached");
    }

    @Test
    void testAgentSystemPromptContainsTools() {
        agent = ReActAgent.builder()
                .llmClient(mockClient)
                .addTool(new CalculatorTool())
                .addTool(new EchoTool())
                .build();

        when(mockClient.chat(any(LLMRequest.class)))
                .thenReturn(createResponse("Final Answer: Done"));

        agent.run("Test");

        ArgumentCaptor<LLMRequest> requestCaptor = ArgumentCaptor.forClass(LLMRequest.class);
        verify(mockClient).chat(requestCaptor.capture());

        LLMRequest capturedRequest = requestCaptor.getValue();
        String systemMessage = capturedRequest.getMessages().get(0).getContent();

        assertThat(systemMessage).contains("Calculator");
        assertThat(systemMessage).contains("Echo");
    }

    @Test
    void testBuilderValidation() {
        assertThatThrownBy(() -> ReActAgent.builder().build()).isInstanceOf(NullPointerException.class);
    }

    private LLMResponse createResponse(String content) {
        return LLMResponse.builder()
                .content(content)
                .model("test-model")
                .build();
    }
}
