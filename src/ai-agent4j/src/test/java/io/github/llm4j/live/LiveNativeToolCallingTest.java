package io.github.llm4j.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.tools.CalculatorTool;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolSpec;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Native tool calling against the real APIs (Gemini and Claude), for whichever credentials are set (see {@link LiveTargets}). The wire formats are
 * tested against recorded responses on every build; this is what shows the real services accept what is sent. Run with
 * {@code mvn -pl ai-agent4j -Plive test}; never part of a default build.
 */
@Tag("live")
class LiveNativeToolCallingTest {

    static Stream<LiveTargets.Target> targets() {
        return LiveProviderContractTest.targets();
    }

    private static LLMClient capable(LiveTargets.Target t) {
        LiveProviderContractTest.present(t);
        LLMClient client = t.client();
        assumeTrue(client.supportsToolCalling(), t.provider() + " uses the text protocol");
        return client;
    }

    @ParameterizedTest(name = "L12 {0}")
    @MethodSource("targets")
    void l12_aModelAsksForAToolAndAnswersFromItsResult(LiveTargets.Target t) {
        LLMClient client = capable(t);
        CalculatorTool calc = new CalculatorTool();
        ToolSpec spec = new ToolSpec(calc.getName(), calc.getDescription(), calc.getParametersSchema());

        LLMRequest first = LLMRequest.builder().addUserMessage("What is 1234 * 5678? Use the calculator tool.").tools(List.of(spec)).maxTokens(500).build();
        LLMResponse asked = client.chat(first);
        assertThat(asked.hasToolCalls()).as("the model called the tool").isTrue();
        assertThat(asked.getFinishReason()).isEqualTo(LLMResponse.FinishReason.TOOL_CALLS);
        assertThat(asked.getToolCalls().get(0).name()).isEqualTo(calc.getName());
        assertThat(asked.getToolCalls().get(0).arguments()).containsKey("expression");

        String result;
        try {
            result = calc.execute(asked.getToolCalls().get(0).arguments());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        LLMResponse answered = client.chat(LLMRequest.builder().addUserMessage("What is 1234 * 5678? Use the calculator tool.")
                .addMessage(Message.assistantToolCalls(asked.getContent(), asked.getToolCalls(), asked.getProviderData()))
                .addMessage(Message.toolResult(asked.getToolCalls().get(0).id(), calc.getName(), result))
                .tools(List.of(spec)).maxTokens(500).build());
        assertThat(answered.getContent().replace(",", "")).contains("7006652");
    }

    @ParameterizedTest(name = "L13 {0}")
    @MethodSource("targets")
    void l13_anAgentOnNativeToolCallingCompletesATask(LiveTargets.Target t) {
        LLMClient client = capable(t);
        AgentResult r = ReActAgent.builder().llmClient(client).addTool(new CalculatorTool()).toolCalling(ReActAgent.ToolCalling.NATIVE).maxIterations(5).build()
                .run("What is 1234 * 5678? Use the calculator.");
        assertThat(r.isCompleted()).isTrue();
        assertThat(r.getSteps()).anyMatch(s -> s.getAction().equalsIgnoreCase("calculator") || s.getAction().equalsIgnoreCase(new CalculatorTool().getName()));
        assertThat(r.getFinalAnswer().replace(",", "")).contains("7006652");
        assertThat(r.isProtocolFollowed()).isTrue();
    }
}
