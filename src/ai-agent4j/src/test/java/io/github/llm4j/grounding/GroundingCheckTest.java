package io.github.llm4j.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.provider.LLMProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * R6, A8, A15 and §4.4: the one central check in {@code DefaultLLMClient} refuses a grounded
 * request the provider cannot serve before any network call, names the provider and the option,
 * honours {@code lenient()}, and runs a client-side search tool when the grounding carries one.
 */
class GroundingCheckTest {

    /** A provider that records what it was sent and answers with a canned grounded response. */
    private static final class Fake implements LLMProvider {
        final String name;
        final GroundingSupport support;
        final Set<GroundingOption> options;
        final boolean tools;
        final List<LLMRequest> seen = new ArrayList<>();
        final List<LLMResponse> replies = new ArrayList<>();

        Fake(String name, GroundingSupport support, Set<GroundingOption> options, boolean tools) {
            this.name = name;
            this.support = support;
            this.options = options;
            this.tools = tools;
        }

        @Override
        public LLMResponse chat(LLMRequest request) {
            seen.add(request);
            return replies.isEmpty()
                    ? LLMResponse.builder().content("answer").finishReason("stop").build()
                    : replies.remove(0);
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            seen.add(request);
            return Stream.of(
                    LLMResponse.builder().content("answer").build(),
                    LLMResponse.builder().content("").finishReason("stop").build());
        }

        @Override
        public String getProviderName() {
            return name;
        }

        @Override
        public void validate() {}

        @Override
        public boolean supportsToolCalling() {
            return tools;
        }

        @Override
        public GroundingSupport groundingSupport() {
            return support;
        }

        @Override
        public Set<GroundingOption> groundingOptions() {
            return options;
        }
    }

    private static LLMRequest ask(Grounding g) {
        return LLMRequest.builder().addUserMessage("What changed?").grounding(g).build();
    }

    @Test
    void aRequestWithoutGroundingIsNotTouched() {
        Fake p = new Fake("plain", GroundingSupport.NONE, Set.of(), false);
        LLMRequest r = LLMRequest.builder().addUserMessage("hi").build();
        new DefaultLLMClient(p).chat(r);
        assertThat(p.seen).containsExactly(r);
        assertThat(r.getGrounding()).isNull();
    }

    @Test
    void aProviderWithoutGroundingRefusesBeforeAnyCallAndNamesItself() {
        Fake p = new Fake("sarvam-chat", GroundingSupport.NONE, Set.of(), false);
        DefaultLLMClient client = new DefaultLLMClient(p);
        assertThatThrownBy(() -> client.chat(ask(Grounding.webSearch().build())))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("sarvam-chat")
                .hasMessageContaining("does not support grounding");
        assertThatThrownBy(() -> client.chatStream(ask(Grounding.webSearch().build())).toList())
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("sarvam-chat");
        assertThat(p.seen).isEmpty();
    }

    @Test
    void anOptionTheProviderCannotHonourIsRefusedByNameBeforeAnyCall() {
        Fake p =
                new Fake("google", GroundingSupport.NATIVE, Set.of(GroundingOption.RECENCY), false);
        DefaultLLMClient client = new DefaultLLMClient(p);
        assertThatThrownBy(
                        () ->
                                client.chat(
                                        ask(
                                                Grounding.webSearch()
                                                        .maxSearches(2)
                                                        .allowDomains("who.int")
                                                        .recency(Recency.YEAR)
                                                        .build())))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("google")
                .hasMessageContaining("maxSearches")
                .hasMessageContaining("allowDomains")
                .hasMessageNotContaining("recency");
        assertThatThrownBy(
                        () ->
                                client.chatStream(ask(Grounding.webSearch().maxSearches(2).build()))
                                        .toList())
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("maxSearches");
        assertThat(p.seen).isEmpty();
        // what it can honour passes
        client.chat(ask(Grounding.webSearch().recency(Recency.YEAR).build()));
        assertThat(p.seen).hasSize(1);
    }

    @Test
    void lenientDropsWhatItCannotHonourAndSaysSoInTheMetadata() {
        Fake p =
                new Fake("google", GroundingSupport.NATIVE, Set.of(GroundingOption.RECENCY), false);
        DefaultLLMClient client = new DefaultLLMClient(p);
        LLMResponse r =
                client.chat(
                        ask(
                                Grounding.webSearch()
                                        .lenient()
                                        .maxSearches(2)
                                        .allowDomains("who.int")
                                        .recency(Recency.WEEK)
                                        .build()));
        assertThat(p.seen).hasSize(1);
        Grounding sent = p.seen.get(0).getGrounding();
        assertThat(sent.options()).containsExactly(GroundingOption.RECENCY);
        assertThat(sent.maxSearches()).isNull();
        assertThat(sent.allowDomains()).isEmpty();
        assertThat(r.getMetadata())
                .containsEntry("grounding.ignored", List.of("maxSearches", "allowDomains"));
        // the same on a stream: the final chunk says it
        p.seen.clear();
        List<LLMResponse> chunks =
                client.chatStream(ask(Grounding.webSearch().lenient().maxSearches(2).build()))
                        .toList();
        assertThat(chunks.get(chunks.size() - 1).getMetadata())
                .containsEntry("grounding.ignored", List.of("maxSearches"));
        assertThat(chunks.get(0).getMetadata()).doesNotContainKey("grounding.ignored");
    }

    @Test
    void lenientDoesNotMakeAnUnsupportedProviderWork() {
        Fake p = new Fake("ollama", GroundingSupport.NONE, Set.of(), false);
        assertThatThrownBy(
                        () ->
                                new DefaultLLMClient(p)
                                        .chat(ask(Grounding.webSearch().lenient().build())))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(p.seen).isEmpty();
    }

    // ── A15: the client-side fallback gives citations of the same shape ─────────────────────

    private static final class FakeSearch implements Tool {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getName() {
            return "WebSearch";
        }

        @Override
        public String getDescription() {
            return "searches the web";
        }

        @Override
        public String execute(Map<String, Object> args) {
            calls.incrementAndGet();
            return "1. WHO sodium guidance - https://www.who.int/news/item/sodium\n2. NIH summary - https://www.nih.gov/sodium (see also https://www.who.int/news/item/sodium).";
        }
    }

    @Test
    void theFallbackOffersTheToolAndFoldsItsResultsIntoCitations() {
        FakeSearch search = new FakeSearch();
        Fake p = new Fake("ollama-with-tools", GroundingSupport.NONE, Set.of(), true);
        p.replies.add(
                LLMResponse.builder()
                        .content("")
                        .finishReason("tool_calls")
                        .toolCalls(
                                List.of(
                                        new ToolCall(
                                                "c1",
                                                "WebSearch",
                                                Map.of("query", "who sodium guidance"))))
                        .tokenUsage(10, 5, 15)
                        .build());
        p.replies.add(
                LLMResponse.builder()
                        .content("Less than 2 g a day.")
                        .finishReason("stop")
                        .tokenUsage(30, 8, 38)
                        .build());

        LLMResponse r =
                new DefaultLLMClient(p).chat(ask(Grounding.webSearch().fallback(search).build()));

        assertThat(search.calls).hasValue(1);
        assertThat(r.getContent()).isEqualTo("Less than 2 g a day.");
        assertThat(r.getCitations())
                .extracting(Citation::url)
                .containsExactly(
                        "https://www.who.int/news/item/sodium", "https://www.nih.gov/sodium");
        assertThat(r.getCitations()).extracting(Citation::index).containsExactly(1, 2);
        assertThat(r.getSearches().count()).isEqualTo(1);
        assertThat(r.getSearches().queries()).containsExactly("who sodium guidance");
        assertThat(r.getTokenUsage().getTotalTokens()).isEqualTo(53);
        // the model was offered the tool, and the grounding itself never reached the provider
        assertThat(p.seen.get(0).getTools()).extracting(t -> t.name()).containsExactly("WebSearch");
        assertThat(p.seen).allSatisfy(q -> assertThat(q.getGrounding()).isNull());
        assertThat(p.seen.get(1).getMessages())
                .anySatisfy(m -> assertThat(m.getRole().getValue()).isEqualTo("tool"));
    }

    @Test
    void theFallbackIsNotUsedWhenTheProviderHasNativeGrounding() {
        FakeSearch search = new FakeSearch();
        Fake p = new Fake("google", GroundingSupport.NATIVE, Set.of(), false);
        new DefaultLLMClient(p).chat(ask(Grounding.webSearch().fallback(search).build()));
        assertThat(search.calls).hasValue(0);
        assertThat(p.seen.get(0).getGrounding()).isNotNull();
    }

    @Test
    void theFallbackNeedsAProviderThatCanCallTools() {
        Fake p = new Fake("sarvam-chat", GroundingSupport.NONE, Set.of(), false);
        assertThatThrownBy(
                        () ->
                                new DefaultLLMClient(p)
                                        .chat(
                                                ask(
                                                        Grounding.webSearch()
                                                                .fallback(new FakeSearch())
                                                                .build())))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("sarvam-chat")
                .hasMessageContaining("tool");
        assertThat(p.seen).isEmpty();
    }

    @Test
    void aModelThatNeverStopsAskingIsBounded() {
        FakeSearch search = new FakeSearch();
        Fake p = new Fake("ollama-with-tools", GroundingSupport.NONE, Set.of(), true);
        for (int i = 0; i < 20; i++) {
            p.replies.add(
                    LLMResponse.builder()
                            .content("")
                            .finishReason("tool_calls")
                            .toolCalls(
                                    List.of(
                                            new ToolCall(
                                                    "c" + i,
                                                    "WebSearch",
                                                    Map.of("query", "q" + i))))
                            .build());
        }
        LLMResponse r =
                new DefaultLLMClient(p)
                        .chat(ask(Grounding.webSearch().maxSearches(2).fallback(search).build()));
        assertThat(search.calls.get()).isEqualTo(2);
        assertThat(r.getSearches().count()).isEqualTo(2);
    }

    @Test
    void aStreamedFallbackEndsWithCitationsAndSearchesInTheFinalChunk() {
        FakeSearch search = new FakeSearch();
        Fake p = new Fake("ollama-with-tools", GroundingSupport.NONE, Set.of(), true);
        p.replies.add(
                LLMResponse.builder()
                        .content("")
                        .finishReason("tool_calls")
                        .toolCalls(List.of(new ToolCall("c1", "WebSearch", Map.of("query", "q"))))
                        .build());
        p.replies.add(LLMResponse.builder().content("Done.").finishReason("stop").build());
        List<LLMResponse> chunks =
                new DefaultLLMClient(p)
                        .chatStream(ask(Grounding.webSearch().fallback(search).build()))
                        .toList();
        assertThat(chunks.stream().map(LLMResponse::getContent).reduce("", String::concat))
                .isEqualTo("Done.");
        LLMResponse last = chunks.get(chunks.size() - 1);
        assertThat(last.getCitations()).hasSize(2);
        assertThat(last.getSearches().count()).isEqualTo(1);
    }

    // ── plumbing on the request and the response ──────────────────────────────────────────────

    @Test
    void theRequestKeepsItsGroundingWhenCopied() {
        Grounding g = Grounding.webSearch().maxSearches(2).build();
        LLMRequest r = LLMRequest.builder().addUserMessage("hi").grounding(g).build();
        assertThat(r.toBuilder().build().getGrounding()).isSameAs(g);
        assertThat(r.toBuilder().build()).isEqualTo(r);
        assertThat(r).isNotEqualTo(LLMRequest.builder().addUserMessage("hi").build());
    }

    @Test
    void theResponseCarriesCitationsAndSearchesAndNeverNull() {
        LLMResponse none = LLMResponse.builder().content("x").build();
        assertThat(none.getCitations()).isEmpty();
        assertThat(none.getSearches()).isNull();
        Citation c = new Citation(1, "https://a.example", null, null, List.of());
        LLMResponse r =
                LLMResponse.builder()
                        .content("x")
                        .citations(List.of(c))
                        .searches(SearchUsage.of(1))
                        .build();
        assertThat(r.getCitations()).containsExactly(c);
        assertThat(r.getSearches().count()).isEqualTo(1);
        assertThat(r.toBuilder().build()).isEqualTo(r);
        assertThat(r).isNotEqualTo(none);
    }
}
