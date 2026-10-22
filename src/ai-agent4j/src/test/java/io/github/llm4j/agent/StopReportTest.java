package io.github.llm4j.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.tools.EchoTool;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * What an agent reports when it is stopped or asked to wrap up (text protocol; the native loop is
 * in {@code StopReportNativeTest}).
 */
class StopReportTest {

    private static final String ECHO =
            "Thought: look.\nAction: echo\nAction Input: {\"input\":\"hi\"}\n";
    private static final String DONE = "Thought: done.\nFinal Answer: finished";

    private static LLMClient scripted(
            List<LLMRequest> seen, Runnable beforeReply, String... replies) {
        LLMClient client = Mockito.mock(LLMClient.class);
        AtomicInteger n = new AtomicInteger();
        Mockito.when(client.chat(Mockito.any(LLMRequest.class)))
                .thenAnswer(
                        call -> {
                            seen.add(call.getArgument(0));
                            if (seen.size() == 1) beforeReply.run();
                            int i = Math.min(n.getAndIncrement(), replies.length - 1);
                            return LLMResponse.builder().content(replies[i]).build();
                        });
        return client;
    }

    private static ReActAgent.Builder builder(LLMClient client) {
        return ReActAgent.builder().llmClient(client).addTool(new EchoTool());
    }

    private static ReActAgent agent(LLMClient client) {
        return builder(client).build();
    }

    @Test
    void aCancelCarriesItsReasonItsOriginWhereItStoppedAndWhatWasDone() {
        RunHandle handle = new RunHandle();
        List<LLMRequest> seen = new ArrayList<>();
        LLMClient client =
                scripted(
                        seen,
                        () -> handle.cancel("the input was wrong", "alice"),
                        ECHO,
                        ECHO,
                        DONE);

        AgentResult result = agent(client).run("do it", handle);

        StopReport stop = result.getStop();
        assertThat(result.isCancelled()).isTrue();
        assertThat(stop.kind()).isEqualTo(StopReport.Kind.CANCEL);
        assertThat(stop.reason()).isEqualTo("the input was wrong");
        assertThat(stop.origin()).isEqualTo("alice");
        assertThat(stop.phase()).isEqualTo(StopReport.Phase.BETWEEN_STEPS);
        assertThat(stop.steps()).hasSize(1);
        assertThat(stop.partialAnswer()).isEqualTo("look.");
        assertThat(seen).hasSize(1);
    }

    @Test
    void aToolThatIsNotKnownToOnlyReadMeansThingsMayHaveChangedUnlessTheHostSaysOtherwise() {
        RunHandle cautious = new RunHandle();
        AgentResult a =
                agent(scripted(new ArrayList<>(), () -> cautious.cancel("x"), ECHO, ECHO, DONE))
                        .run("do it", cautious);
        assertThat(a.getStop().mayHaveChangedThings())
                .as("an unclassified tool is assumed to change things")
                .isTrue();

        RunHandle told = new RunHandle();
        AgentResult b =
                builder(scripted(new ArrayList<>(), () -> told.cancel("x"), ECHO, ECHO, DONE))
                        .effectClassifier((tool, args) -> false)
                        .build()
                        .run("do it", told);
        assertThat(b.getStop().mayHaveChangedThings())
                .as("the host knows echo only reads")
                .isFalse();
    }

    @Test
    void aCancelBeforeAnythingRanReportsBeforeStartAndNothingChanged() {
        RunHandle handle = new RunHandle();
        handle.cancel("changed my mind", "bob");
        List<LLMRequest> seen = new ArrayList<>();

        AgentResult result = agent(scripted(seen, () -> {}, DONE)).run("do it", handle);

        assertThat(result.getStop().phase()).isEqualTo(StopReport.Phase.BEFORE_START);
        assertThat(result.getStop().steps()).isEmpty();
        assertThat(result.getStop().mayHaveChangedThings()).isFalse();
        assertThat(seen).isEmpty();
    }

    @Test
    void theFirstReasonIsTheOneKept() {
        RunHandle handle = new RunHandle();
        handle.cancel("first", "a");
        handle.cancel("second", "b");
        AgentResult result = agent(scripted(new ArrayList<>(), () -> {}, DONE)).run("x", handle);
        assertThat(result.getStop().reason()).isEqualTo("first");
        assertThat(result.getStop().origin()).isEqualTo("a");
    }

    @Test
    void aWrapUpMakesOneLastCallAndTakesItsAnswer() {
        RunHandle handle = new RunHandle();
        List<LLMRequest> seen = new ArrayList<>();
        LLMClient client =
                scripted(
                        seen,
                        () -> handle.wrapUp("budget is low", "supervisor"),
                        ECHO,
                        "Thought: enough.\nFinal Answer: what I have so far",
                        DONE);
        List<String> events = new ArrayList<>();
        AgentEventListener listener =
                new AgentEventListener() {
                    public void onThought(String t) {}

                    public void onAction(String n, String i) {}

                    public void onObservation(String o) {}

                    @Override
                    public void onWrappedUp() {
                        events.add("wrapped");
                    }
                };

        AgentResult result = builder(client).addListener(listener).build().run("do it", handle);

        assertThat(result.isWrappedUp()).isTrue();
        assertThat(result.isCancelled()).isFalse();
        assertThat(result.isCompleted()).isFalse();
        assertThat(result.getFinalAnswer()).isEqualTo("what I have so far");
        assertThat(result.getStop().kind()).isEqualTo(StopReport.Kind.WRAP_UP);
        assertThat(result.getStop().reason()).isEqualTo("budget is low");
        assertThat(result.getStop().origin()).isEqualTo("supervisor");
        assertThat(seen).hasSize(2); // the call under way, and the one wrap-up call
        assertThat(seen.get(1).getMessages().get(1).getContent()).contains("Stop using tools now");
        assertThat(events).containsExactly("wrapped");
        assertThat(result.getUsage().getLlmCalls())
                .as("the call under way and the wrap-up call")
                .isEqualTo(2);
    }

    @Test
    void aCancelOverridesAWrapUp() {
        RunHandle handle = new RunHandle();
        handle.wrapUp("a", "x");
        handle.cancel("b", "y");
        AgentResult result = agent(scripted(new ArrayList<>(), () -> {}, DONE)).run("x", handle);
        assertThat(result.isCancelled()).isTrue();
        assertThat(result.isWrappedUp()).isFalse();
        assertThat(result.getStop().reason()).isEqualTo("b");
    }

    @Test
    void steeringKeepsAHistoryWithWhoSentIt() {
        RunHandle handle = new RunHandle();
        handle.steer("focus on the EU", "alice");
        handle.steer("and be brief");
        assertThat(handle.steers())
                .extracting(RunHandle.Steer::text)
                .containsExactly("focus on the EU", "and be brief");
        assertThat(handle.steers())
                .extracting(RunHandle.Steer::origin)
                .containsExactly("alice", null);
    }

    @SuppressWarnings("unused")
    private static Stream<LLMResponse> none() {
        return Stream.empty();
    }
}
