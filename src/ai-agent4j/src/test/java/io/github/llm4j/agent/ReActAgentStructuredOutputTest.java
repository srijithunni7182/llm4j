package io.github.llm4j.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.tools.CalculatorTool;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Covers structured (JSON) final answers and role instructions layered on the ReAct protocol. */
class ReActAgentStructuredOutputTest {

    private final LLMClient client = mock(LLMClient.class);

    @Test
    void objectFinalAnswerIsKeptAsJsonText() {
        when(client.chat(any(LLMRequest.class)))
                .thenReturn(response("```json\n"
                        + "{\"thought\": \"done\", \"final_answer\": {\"score\": 9, \"verdict\": \"SHIP\"}}\n"
                        + "```"));

        AgentResult result = ReActAgent.builder().llmClient(client).build().run("Score this");

        assertThat(result.isCompleted()).isTrue();
        assertThat(result.getIterations()).isEqualTo(1);
        assertThat(result.getFinalAnswer()).contains("\"score\":9").contains("\"verdict\":\"SHIP\"");
    }

    @Test
    void bareJsonPayloadIsTreatedAsTheFinalAnswer() {
        when(client.chat(any(LLMRequest.class)))
                .thenReturn(response("```json\n{\"score\": 7, \"verdict\": \"REVISE\"}\n```"));

        AgentResult result = ReActAgent.builder().llmClient(client).build().run("Score this");

        assertThat(result.isCompleted()).isTrue();
        assertThat(result.getIterations()).isEqualTo(1);
        assertThat(result.getFinalAnswer()).contains("\"verdict\":\"REVISE\"");
    }

    @Test
    void instructionsAreLayeredOnTopOfToolProtocol() {
        when(client.chat(any(LLMRequest.class)))
                .thenReturn(response("```json\n{\"thought\": \"ok\", \"final_answer\": \"hi\"}\n```"));

        ReActAgent.builder()
                .llmClient(client)
                .instructions("You are TrendScout.")
                .addTool(new CalculatorTool())
                .build()
                .run("Hello");

        ArgumentCaptor<LLMRequest> captor = ArgumentCaptor.forClass(LLMRequest.class);
        verify(client).chat(captor.capture());
        String systemPrompt = captor.getValue().getMessages().stream()
                .filter(m -> m.getRole() == Message.Role.SYSTEM)
                .map(Message::getContent)
                .findFirst()
                .orElseThrow();

        assertThat(systemPrompt).startsWith("You are TrendScout.");
        assertThat(systemPrompt).contains(new CalculatorTool().getName());
        assertThat(systemPrompt).contains("action_input");
    }

    @Test
    void systemPromptStillReplacesTheProtocolVerbatim() {
        when(client.chat(any(LLMRequest.class))).thenReturn(response("plain answer"));

        ReActAgent.builder()
                .llmClient(client)
                .systemPrompt("Verbatim.")
                .instructions("ignored")
                .build()
                .run("Hello");

        ArgumentCaptor<LLMRequest> captor = ArgumentCaptor.forClass(LLMRequest.class);
        verify(client).chat(captor.capture());
        assertThat(captor.getValue().getMessages().get(0).getContent()).isEqualTo("Verbatim.");
    }

    private static LLMResponse response(String content) {
        return LLMResponse.builder().content(content).model("test").build();
    }
}
