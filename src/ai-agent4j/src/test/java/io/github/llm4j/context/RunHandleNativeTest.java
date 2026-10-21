package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.RunHandle;
import io.github.llm4j.model.Message;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The run handle in the native tool-calling loop. */
class RunHandleNativeTest {

    @Test
    void steeringIsAUserMessageAfterTheToolResultsAndBeforeTheNextCall() {
        RunHandle handle = new RunHandle();
        ScriptedModel model =
                ScriptedModel.by(
                        true,
                        true,
                        n -> {
                            if (n == 0) {
                                handle.steer("stick to the budget");
                                return ScriptedModel.calls(
                                        "looking",
                                        ScriptedModel.call("c0", "search", Map.of("q", "a")));
                            }
                            return ScriptedModel.answer("done");
                        });

        AgentResult r =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(ReActAgentCompactionNativeTest.search(10))
                        .build()
                        .run("Q?", handle);

        assertThat(r.isCompleted()).isTrue();
        List<Message> second = model.requests.get(1).getMessages();
        Message last = second.get(second.size() - 1);
        assertThat(last.getRole()).isEqualTo(Message.Role.USER);
        assertThat(last.getContent()).contains("stick to the budget");
        // the tool's result still follows its call directly
        assertThat(second.get(second.size() - 2).getRole()).isEqualTo(Message.Role.TOOL);
        ReActAgentCompactionNativeTest.assertPairsWhole(model.requests.get(1));
    }

    @Test
    void cancelBetweenTheCallsOfOneTurnRunsNoMoreOfThem() {
        RunHandle handle = new RunHandle();
        int[] ran = {0};
        io.github.llm4j.agent.Tool first =
                new ScriptedModel.FixedTool(
                        "search",
                        n -> {
                            ran[0]++;
                            handle.cancel();
                            return "r";
                        });
        ScriptedModel model =
                ScriptedModel.by(
                        true,
                        true,
                        n ->
                                ScriptedModel.calls(
                                        "two at once",
                                        ScriptedModel.call("a", "search", Map.of("q", "1")),
                                        ScriptedModel.call("b", "search", Map.of("q", "2"))));

        AgentResult r =
                ReActAgent.builder().llmClient(model).addTool(first).build().run("Q?", handle);

        assertThat(r.isCancelled()).isTrue();
        assertThat(ran[0]).isEqualTo(1);
        assertThat(model.requests).hasSize(1);
    }
}
