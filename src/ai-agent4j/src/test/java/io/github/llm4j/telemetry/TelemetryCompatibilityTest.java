package io.github.llm4j.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.model.LLMRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Adding observation changes nothing about what the model is sent or what a tool receives (R19, R20). */
class TelemetryCompatibilityTest {

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void anOldStyleListenerWithOnlyTheOriginalMethodsStillWorks(boolean nativeLoop) {
        List<String> seen = new ArrayList<>();
        AgentEventListener old = new AgentEventListener() {
            @Override public void onThought(String thought) { seen.add("thought"); }
            @Override public void onAction(String toolName, String toolInput) { seen.add("action"); }
            @Override public void onObservation(String observation) { seen.add("observation"); }
        };
        ReActAgent.builder().llmClient(Scripts.oneToolStep(nativeLoop, "t", "x")).addTool(TestTool.returning("t", "ok")).addListener(old).build().run("Go.");
        assertThat(seen).contains("action", "observation");
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void theModelIsSentTheSameWithOrWithoutListenersAndStatistics(boolean nativeLoop) {
        List<LLMRequest> without = new ArrayList<>();
        List<LLMRequest> with = new ArrayList<>();
        ReActAgent.builder().llmClient(recording(Scripts.toolSteps(nativeLoop, "t", 3), without)).addTool(TestTool.returning("t", "result")).maxIterations(10).build().run("Go.");
        ReActAgent.builder().llmClient(recording(Scripts.toolSteps(nativeLoop, "t", 3), with)).addTool(TestTool.returning("t", "result")).maxIterations(10)
                .addListener(new TelemetryListener()).addListener(new io.github.llm4j.agent.StatsCollector()).build().run("Go.");
        assertThat(with).hasSameSizeAs(without);
        for (int i = 0; i < with.size(); i++) {
            assertThat(with.get(i).getMessages()).as("request " + i).isEqualTo(without.get(i).getMessages());
        }
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void aToolReceivesTheSameArgumentsWithOrWithoutListeners(boolean nativeLoop) {
        List<Object> a = new ArrayList<>();
        List<Object> b = new ArrayList<>();
        ReActAgent.builder().llmClient(Scripts.oneToolStep(nativeLoop, "t", "x")).addTool(new TestTool("t", (args, n) -> { a.add(args); return "ok"; })).build().run("Go.");
        ReActAgent.builder().llmClient(Scripts.oneToolStep(nativeLoop, "t", "x")).addTool(new TestTool("t", (args, n) -> { b.add(args); return "ok"; }))
                .addListener(new TelemetryListener()).build().run("Go.");
        assertThat(b).isEqualTo(a);
    }

    private static io.github.llm4j.LLMClient recording(io.github.llm4j.LLMClient inner, List<LLMRequest> into) {
        return new io.github.llm4j.LLMClient() {
            @Override public io.github.llm4j.model.LLMResponse chat(LLMRequest request) { into.add(request); return inner.chat(request); }
            @Override public java.util.stream.Stream<io.github.llm4j.model.LLMResponse> chatStream(LLMRequest request) { return inner.chatStream(request); }
            @Override public boolean supportsToolCalling() { return inner.supportsToolCalling(); }
        };
    }
}
