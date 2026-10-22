package io.github.llm4j.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Observation must never break the run it observes (R21). */
class TelemetryFaultTest {

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void aListenerThatThrowsFromTheToolCallbackDoesNotFailTheRun(boolean nativeLoop) {
        AgentEventListener bad =
                new AgentEventListener() {
                    @Override
                    public void onThought(String t) {}

                    @Override
                    public void onAction(String a, String i) {}

                    @Override
                    public void onObservation(String o) {}

                    @Override
                    public void onToolCall(ToolCallEvent e) {
                        throw new IllegalStateException("listener bug");
                    }

                    @Override
                    public void onModelCall(ModelCallEvent e) {
                        throw new IllegalStateException("listener bug");
                    }
                };
        TelemetryListener good = new TelemetryListener();
        AgentResult r =
                ReActAgent.builder()
                        .llmClient(Scripts.oneToolStep(nativeLoop, "t", "x"))
                        .addTool(TestTool.returning("t", "ok"))
                        .addListener(bad)
                        .addListener(good)
                        .build()
                        .run("Go.");
        assertThat(r.getFinalAnswer()).isEqualTo("done");
        assertThat(good.tools).hasSize(1); // the listener after the broken one still heard it
        assertThat(good.models).hasSize(2);
        assertThat(r.getStats().totalModelCalls()).isEqualTo(2);
        assertThat(r.getStats().tool("t").calls())
                .isEqualTo(1); // and the statistics did not depend on the listeners
    }
}
