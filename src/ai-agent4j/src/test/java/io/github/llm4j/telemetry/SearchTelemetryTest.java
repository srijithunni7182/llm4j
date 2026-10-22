package io.github.llm4j.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.grounding.Citation;
import io.github.llm4j.grounding.SearchUsage;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * §4.5: a model-call event carries how many searches ran and how many citations came back (counts,
 * never text), and the statistics add them up.
 */
class SearchTelemetryTest {

    private static LLMResponse grounded(String model, Integer searches, int citations) {
        List<Citation> cs = new ArrayList<>();
        for (int i = 1; i <= citations; i++)
            cs.add(
                    new Citation(
                            i,
                            "https://example.org/" + i,
                            "title " + i,
                            "snippet " + i,
                            List.of()));
        return LLMResponse.builder()
                .content("Final Answer: done")
                .model(model)
                .tokenUsage(10, 5, 15)
                .finishReason("stop")
                .searches(
                        searches == null
                                ? new SearchUsage(null, List.of("secret query"), null)
                                : new SearchUsage(searches, List.of("secret query"), 0.005))
                .citations(cs)
                .build();
    }

    private static io.github.llm4j.agent.AgentEventListener listening(List<ModelCallEvent> into) {
        return new io.github.llm4j.agent.AgentEventListener() {
            @Override
            public void onModelCall(ModelCallEvent event) {
                into.add(event);
            }

            @Override
            public void onThought(String thought) {}

            @Override
            public void onAction(String toolName, String toolInput) {}

            @Override
            public void onObservation(String observation) {}
        };
    }

    private static LLMClient client(LLMResponse response) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                return response;
            }

            @Override
            public java.util.stream.Stream<LLMResponse> chatStream(LLMRequest request) {
                return java.util.stream.Stream.of(response);
            }
        };
    }

    @Test
    void theEventCarriesCountsOnly() {
        List<ModelCallEvent> seen = new ArrayList<>();
        ReActAgent agent =
                ReActAgent.builder()
                        .llmClient(client(grounded("m", 2, 3)))
                        .addListener(listening(seen))
                        .build();
        agent.run("what is new?");
        assertThat(seen).hasSize(1);
        ModelCallEvent e = seen.get(0);
        assertThat(e.searches()).isEqualTo(2);
        assertThat(e.citations()).isEqualTo(3);
        assertThat(e.toString())
                .doesNotContain("example.org")
                .doesNotContain("secret query")
                .doesNotContain("snippet");
    }

    @Test
    void anUnknownCountStaysNullAndAPlainCallHasNone() {
        List<ModelCallEvent> seen = new ArrayList<>();
        ReActAgent.builder()
                .llmClient(client(grounded("m", null, 1)))
                .addListener(listening(seen))
                .build()
                .run("x");
        assertThat(seen.get(0).searches()).isNull();
        assertThat(seen.get(0).citations()).isEqualTo(1);

        List<ModelCallEvent> plain = new ArrayList<>();
        ReActAgent.builder()
                .llmClient(
                        client(
                                LLMResponse.builder()
                                        .content("Final Answer: ok")
                                        .tokenUsage(1, 1, 2)
                                        .finishReason("stop")
                                        .build()))
                .addListener(listening(plain))
                .build()
                .run("x");
        assertThat(plain.get(0).searches()).isNull();
        assertThat(plain.get(0).citations()).isZero();
    }

    @Test
    void theStatisticsAddSearchesAndCitationsPerModel() {
        StatsAccumulator acc = new StatsAccumulator();
        acc.onModelCall(
                ModelCallEvent.builder()
                        .model("a")
                        .totalTokens(1)
                        .searches(2)
                        .citations(3)
                        .build());
        acc.onModelCall(
                ModelCallEvent.builder()
                        .model("a")
                        .totalTokens(1)
                        .searches(1)
                        .citations(1)
                        .build());
        acc.onModelCall(ModelCallEvent.builder().model("a").totalTokens(1).build());
        acc.onModelCall(ModelCallEvent.builder().model("b").totalTokens(1).citations(2).build());
        RunStats s = acc.snapshot();
        assertThat(s.model("a").searches()).isEqualTo(3);
        assertThat(s.model("a").citations()).isEqualTo(4);
        assertThat(s.model("b").searches()).isZero();
        assertThat(s.totalSearches()).isEqualTo(3);
    }

    @Test
    void aFailedCallAddsNoSearches() {
        StatsAccumulator acc = new StatsAccumulator();
        acc.onModelCall(
                ModelCallEvent.builder()
                        .model("a")
                        .searches(5)
                        .failed(ErrorCategory.OTHER, "X")
                        .build());
        assertThat(acc.snapshot().model("a").searches()).isZero();
    }

    @Test
    void theOldEventShapeStillWorks() {
        ModelCallEvent e =
                new ModelCallEvent(
                        0,
                        "r",
                        "m",
                        java.time.Instant.EPOCH,
                        1,
                        0,
                        1,
                        List.of(),
                        1,
                        1,
                        2,
                        false,
                        null,
                        LLMResponse.FinishReason.STOP,
                        0,
                        ModelCallEvent.Outcome.OK,
                        null,
                        null);
        assertThat(e.searches()).isNull();
        assertThat(e.citations()).isZero();
    }
}
