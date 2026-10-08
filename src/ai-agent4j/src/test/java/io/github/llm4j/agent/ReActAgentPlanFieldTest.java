package io.github.llm4j.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.tools.CalculatorTool;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Found by the live suite: Claude Opus 5.5 refuses (category reasoning_extraction) a prompt that asks it
 * to fill in a "thought" field. The ReAct prompt asks for a "plan" note instead; replies with either are read.
 */
class ReActAgentPlanFieldTest {

    static LLMClient scripted(List<LLMRequest> seen, String... replies) {
        return new LLMClient() {
            int i;

            @Override
            public LLMResponse chat(LLMRequest request) {
                seen.add(request);
                return LLMResponse.builder().content(replies[Math.min(i++, replies.length - 1)]).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
    }

    @Test
    void thePromptAsksForAPlanAndThePlanIsReplayedAsPlan() {
        List<LLMRequest> seen = new ArrayList<>();
        AgentResult r = ReActAgent.builder().addTool(new CalculatorTool()).llmClient(scripted(seen,
                "```json\n{\"plan\": \"multiply\", \"action\": \"calculator\", \"action_input\": {\"expression\": \"6*7\"}}\n```",
                "```json\n{\"plan\": \"I have the answer\", \"final_answer\": \"42\"}\n```")).build().run("6*7?");
        assertThat(r.getFinalAnswer()).isEqualTo("42");
        assertThat(r.getSteps().get(0).getThought()).isEqualTo("multiply");
        String system = seen.get(0).getMessages().get(0).getContent();
        assertThat(system).contains("\"plan\"").doesNotContain("\"thought\"");
        assertThat(seen.get(1).getMessages().get(1).getContent()).contains("Plan: multiply").doesNotContain("Thought:");
    }

    @Test
    void oldThoughtRepliesStillWorkAndALonePlanIsAnAnswer() {
        List<LLMRequest> seen = new ArrayList<>();
        AgentResult old = ReActAgent.builder().llmClient(scripted(seen,
                "```json\n{\"thought\": \"easy\", \"final_answer\": \"yes\"}\n```")).build().run("ok?");
        assertThat(old.getFinalAnswer()).isEqualTo("yes");

        AgentResult structured = ReActAgent.builder().llmClient(scripted(seen,
                "```json\n{\"plan\": \"ship on Friday\", \"owner\": \"Asha\"}\n```")).build().run("Make a plan as JSON");
        assertThat(structured.getFinalAnswer()).contains("\"plan\":\"ship on Friday\"").contains("\"owner\":\"Asha\"");
    }
}
