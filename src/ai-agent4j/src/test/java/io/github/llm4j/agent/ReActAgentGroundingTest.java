package io.github.llm4j.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.grounding.Citation;
import io.github.llm4j.grounding.Grounding;
import io.github.llm4j.grounding.GroundingSupport;
import io.github.llm4j.grounding.SearchUsage;
import io.github.llm4j.grounding.Span;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.provider.LLMProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * §4.6 (8g): {@code ReActAgent.grounding(...)} puts the grounding on every model call of the run,
 * and {@code AgentResult.getCitations()} is the union of the sources, de-duplicated by URL.
 */
class ReActAgentGroundingTest {

    private static Citation cite(int index, String url, String title) {
        return new Citation(index, url, title, "snippet", List.of(new Span(0, 3)));
    }

    /** Answers in order, grounded; remembers every request. */
    private static final class Scripted implements LLMClient {
        final List<LLMRequest> seen = new ArrayList<>();
        final List<LLMResponse> replies = new ArrayList<>();
        final boolean tools;
        final AtomicInteger n = new AtomicInteger();

        Scripted(boolean tools, LLMResponse... replies) {
            this.tools = tools;
            this.replies.addAll(List.of(replies));
        }

        @Override
        public LLMResponse chat(LLMRequest request) {
            seen.add(request);
            return replies.get(Math.min(n.getAndIncrement(), replies.size() - 1));
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            return Stream.of(chat(request));
        }

        @Override
        public boolean supportsToolCalling() {
            return tools;
        }
    }

    private static LLMResponse textReply(String content, int searches, Citation... cites) {
        return LLMResponse.builder()
                .content(content)
                .model("m")
                .tokenUsage(10, 5, 15)
                .finishReason("stop")
                .citations(List.of(cites))
                .searches(SearchUsage.of(searches))
                .build();
    }

    private static Tool notebook() {
        return new Tool() {
            @Override
            public String getName() {
                return "notebook";
            }

            @Override
            public String getDescription() {
                return "writes a note";
            }

            @Override
            public String execute(Map<String, Object> args) {
                return "noted";
            }
        };
    }

    @Test
    void everyMainCallOfTheTextLoopCarriesTheGroundingAndTheResultGathersTheSources() {
        Grounding g = Grounding.webSearch().maxSearches(2).build();
        Scripted model =
                new Scripted(
                        false,
                        textReply(
                                "```json\n{\"thought\":\"look\",\"action\":\"notebook\",\"action_input\":{}}\n```",
                                1,
                                cite(1, "https://a.example", "A"),
                                cite(2, "https://b.example", "B")),
                        textReply(
                                "```json\n{\"thought\":\"done\",\"final_answer\":\"yes\"}\n```",
                                2,
                                cite(1, "https://b.example", "B again"),
                                cite(2, "https://c.example", "C")));
        AgentResult r =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(notebook())
                        .grounding(g)
                        .build()
                        .run("what is new?");

        assertThat(model.seen).hasSize(2).allSatisfy(q -> assertThat(q.getGrounding()).isSameAs(g));
        assertThat(r.getFinalAnswer()).isEqualTo("yes");
        // the union, by URL, first sighting wins, numbered from 1, without positions
        assertThat(r.getCitations())
                .extracting(Citation::url)
                .containsExactly("https://a.example", "https://b.example", "https://c.example");
        assertThat(r.getCitations()).extracting(Citation::index).containsExactly(1, 2, 3);
        assertThat(r.getCitations().get(1).title()).isEqualTo("B");
        assertThat(r.getCitations()).allSatisfy(c -> assertThat(c.spans()).isEmpty());
        assertThat(r.getStats().totalSearches()).isEqualTo(3);
        assertThat(r.getStats().model("m").citations()).isEqualTo(4);
    }

    @Test
    void theNativeToolLoopAlsoCarriesItAndCollectsTheSources() {
        Grounding g = Grounding.webSearch().build();
        Scripted model =
                new Scripted(
                        true,
                        LLMResponse.builder()
                                .content("")
                                .model("m")
                                .finishReason("tool_calls")
                                .toolCalls(List.of(new ToolCall("c1", "notebook", Map.of())))
                                .citations(List.of(cite(1, "https://a.example", "A")))
                                .searches(SearchUsage.of(1))
                                .tokenUsage(10, 5, 15)
                                .build(),
                        textReply("All done.", 1, cite(1, "https://a.example", "A")));
        AgentResult r =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(notebook())
                        .grounding(g)
                        .build()
                        .run("write a note about the news");

        assertThat(model.seen).hasSize(2).allSatisfy(q -> assertThat(q.getGrounding()).isSameAs(g));
        assertThat(r.getCitations()).extracting(Citation::url).containsExactly("https://a.example");
        assertThat(r.getStats().totalSearches()).isEqualTo(2);
    }

    @Test
    void anAgentWithoutGroundingSendsNoneAndHasNoSources() {
        Scripted model =
                new Scripted(
                        false,
                        LLMResponse.builder()
                                .content(
                                        "```json\n{\"thought\":\"t\",\"final_answer\":\"hi\"}\n```")
                                .tokenUsage(1, 1, 2)
                                .build());
        AgentResult r = ReActAgent.builder().llmClient(model).build().run("hi");
        assertThat(model.seen.get(0).getGrounding()).isNull();
        assertThat(r.getCitations()).isEmpty();
        assertThat(r.getStats().totalSearches()).isZero();
    }

    @Test
    void aStreamingAgentKeepsTheSourcesOfTheFinalChunk() {
        Scripted model =
                new Scripted(
                        false,
                        textReply(
                                "```json\n{\"thought\":\"t\",\"final_answer\":\"hi\"}\n```",
                                1,
                                cite(1, "https://a.example", "A")));
        AgentResult r =
                ReActAgent.builder()
                        .llmClient(model)
                        .streaming(true)
                        .grounding(Grounding.webSearch())
                        .build()
                        .run("hi");
        assertThat(r.getCitations()).extracting(Citation::url).containsExactly("https://a.example");
    }

    @Test
    void aClientThatCannotGroundRefusesTheFirstCallNamingTheProvider() {
        LLMProvider none =
                new LLMProvider() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        throw new AssertionError("must not be called");
                    }

                    @Override
                    public String getProviderName() {
                        return "sarvam-chat";
                    }

                    @Override
                    public void validate() {}

                    @Override
                    public GroundingSupport groundingSupport() {
                        return GroundingSupport.NONE;
                    }
                };
        ReActAgent agent =
                ReActAgent.builder()
                        .llmClient(new DefaultLLMClient(none))
                        .grounding(Grounding.webSearch())
                        .build();
        assertThatThrownBy(() -> agent.run("news?"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("sarvam-chat");
    }

    @Test
    void theGroundingSurvivesCopyingTheAgentAndTheResult() {
        Grounding g = Grounding.webSearch().maxSearches(1).build();
        Scripted model =
                new Scripted(
                        false,
                        textReply(
                                "```json\n{\"thought\":\"t\",\"final_answer\":\"hi\"}\n```",
                                1,
                                cite(1, "https://a.example", "A")));
        ReActAgent agent = ReActAgent.builder().llmClient(model).grounding(g).build();
        agent.toBuilder().build().run("x");
        assertThat(model.seen.get(0).getGrounding()).isSameAs(g);
        AgentResult r = agent.run("x");
        assertThat(r.toBuilder().build().getCitations()).hasSize(1);
    }
}
