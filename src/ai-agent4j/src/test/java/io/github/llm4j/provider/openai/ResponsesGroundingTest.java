package io.github.llm4j.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.grounding.Citation;
import io.github.llm4j.grounding.Grounding;
import io.github.llm4j.grounding.GroundingOption;
import io.github.llm4j.grounding.GroundingSupport;
import io.github.llm4j.grounding.Recency;
import io.github.llm4j.grounding.SearchUsage;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.ToolSchema;
import io.github.llm4j.model.ToolSpec;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.fixtures.Fixtures;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Grounding through the Responses schema (8f; A9, A10, A11, A19; R6 to R9): the request the dialect
 * writes for each option, and the citations and search usage read from responses <b>recorded from
 * the real APIs</b> (Perplexity Agent API, OpenAI Responses, xAI Responses; 2026-10-22).
 */
class ResponsesGroundingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One vendor with the facts its recorded grounded answer must yield. */
    record V(
            String directory,
            Function<LLMConfig, LLMProvider> provider,
            String model,
            int citations,
            String basis,
            int searches,
            int queries,
            Double cost) {
        @Override
        public String toString() {
            return directory;
        }
    }

    static List<V> vendors() {
        return List.of(
                new V(
                        "perplexity-agent",
                        c -> new OpenAiResponsesProvider(ResponsesDialect.PERPLEXITY, c),
                        "openai/gpt-6-luna",
                        3,
                        "consulted",
                        1,
                        2,
                        0.0025),
                new V(
                        "openai-responses",
                        c -> new OpenAiResponsesProvider(ResponsesDialect.OPENAI, c),
                        "gpt-6-luna",
                        1,
                        "cited",
                        1,
                        2,
                        null),
                new V(
                        "xai-responses",
                        c -> new OpenAiResponsesProvider(ResponsesDialect.XAI, c),
                        "grok-4.3",
                        2,
                        "cited",
                        2,
                        2,
                        null));
    }

    private MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private LLMProvider provider(V v) {
        return v.provider()
                .apply(
                        LLMConfig.builder()
                                .apiKey("k-1234567")
                                .baseUrl(server.url("/v1").toString())
                                .defaultModel(v.model())
                                .build());
    }

    private void enqueue(V v, String name, String type) {
        Fixtures f = Fixtures.load(v.directory() + "/" + name + ".fixture");
        assertThat(f.source()).isEqualTo("recorded");
        server.enqueue(new MockResponse().setHeader("Content-Type", type).setBody(f.body()));
    }

    private static LLMRequest ask(Grounding.Builder g) {
        return LLMRequest.builder().addUserMessage("What is the repo rate?").grounding(g).build();
    }

    private JsonNode sent() throws Exception {
        RecordedRequest r = server.takeRequest();
        return JSON.readTree(r.getBody().readUtf8());
    }

    // ── reading what the vendors really sent ─────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("vendors")
    void aGroundedAnswerHasTypedCitationsAndTheVendorsOwnSearchCount(V v) {
        enqueue(v, "grounded", "application/json");
        LLMResponse r = provider(v).chat(ask(Grounding.webSearch()));

        assertThat(r.getContent()).isNotBlank();
        assertThat(r.getCitations()).hasSize(v.citations());
        assertThat(r.getCitations())
                .extracting(Citation::index)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, v.citations()).boxed().toList());
        assertThat(r.getCitations()).allSatisfy(c -> assertThat(c.url()).startsWith("http"));
        assertThat(r.getMetadata()).containsEntry("grounding.citation_basis", v.basis());
        SearchUsage u = r.getSearches();
        assertThat(u.count()).isEqualTo(v.searches());
        assertThat(u.queries()).hasSize(v.queries());
        assertThat(u.estimatedCostUsd()).isEqualTo(v.cost());
        // the spans lie inside the answer, and are kept only where the vendor gave positions
        for (Citation c : r.getCitations())
            for (var s : c.spans())
                assertThat(s.end()).isLessThanOrEqualTo(r.getContent().length());
    }

    @Test
    void perplexityListsWhatItConsultedWithTitlesAndSnippets() {
        V v = vendors().get(0);
        enqueue(v, "grounded", "application/json");
        LLMResponse r = provider(v).chat(ask(Grounding.webSearch()));
        assertThat(r.getCitations())
                .allSatisfy(
                        c -> {
                            assertThat(c.title()).isNotBlank();
                            assertThat(c.snippet()).isNotBlank();
                            assertThat(c.spans()).isEmpty();
                        });
    }

    @Test
    void openAiCitesWithTitlesAndTheLinkTheModelWroteAsTheSpan() {
        V v = vendors().get(1);
        enqueue(v, "grounded", "application/json");
        LLMResponse r = provider(v).chat(ask(Grounding.webSearch()));
        Citation c = r.getCitations().get(0);
        assertThat(c.title()).isNotBlank();
        assertThat(c.spans()).isNotEmpty();
        String cited = r.getContent().substring(c.spans().get(0).start(), c.spans().get(0).end());
        assertThat(cited).contains(c.url().substring(0, 20));
    }

    @Test
    void xaiTitlesThatAreOnlyTheCitationNumberAreDropped() {
        V v = vendors().get(2);
        enqueue(v, "grounded", "application/json");
        LLMResponse r = provider(v).chat(ask(Grounding.webSearch()));
        assertThat(r.getCitations()).extracting(Citation::title).containsOnlyNulls();
        assertThat(r.getCitations()).allSatisfy(c -> assertThat(c.spans()).isNotEmpty());
        assertThat(r.getSearches().estimatedCostUsd())
                .isNull(); // the cost it reports is the whole call's
    }

    @Test
    void anOpenAiSearchStillRunningWhenTheAnswerEndedIsNotCounted() {
        V v = vendors().get(1);
        enqueue(v, "grounded-options", "application/json");
        LLMResponse r = provider(v).chat(ask(Grounding.webSearch().maxSearches(2)));
        assertThat(r.getSearches().count()).isEqualTo(2);
    }

    @ParameterizedTest
    @MethodSource("vendors")
    void aResponseWithoutGroundingCarriesNoCitationsOrSearchUsage(V v) {
        enqueue(v, "grounded", "application/json");
        LLMResponse r = provider(v).chat(LLMRequest.builder().addUserMessage("hi").build());
        assertThat(r.getCitations()).isEmpty();
        assertThat(r.getSearches()).isNull();
        assertThat(r.getMetadata()).doesNotContainKey("grounding.citation_basis");
    }

    // ── streaming agrees with the whole answer (R9) ──────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("vendors")
    void aStreamedAnswerAgreesWithTheWholeOne(V v) throws Exception {
        String whole = Fixtures.load(v.directory() + "/grounded.fixture").body();
        enqueue(v, "grounded", "application/json");
        LLMResponse r = provider(v).chat(ask(Grounding.webSearch()));

        // the same response, delivered the way the vendor streams it: text deltas, then the whole
        // response object in the completed event
        String sse =
                "event: response.output_text.delta\ndata: "
                        + JSON.writeValueAsString(
                                JSON.createObjectNode()
                                        .put("type", "response.output_text.delta")
                                        .put("delta", r.getContent()))
                        + "\n\nevent: response.completed\ndata: "
                        + JSON.writeValueAsString(
                                JSON.createObjectNode()
                                        .put("type", "response.completed")
                                        .set("response", JSON.readTree(whole)))
                        + "\n\n";
        server.enqueue(
                new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse));
        List<LLMResponse> chunks = provider(v).chatStream(ask(Grounding.webSearch())).toList();

        LLMResponse last = chunks.get(chunks.size() - 1);
        assertThat(chunks.stream().map(LLMResponse::getContent).collect(Collectors.joining()))
                .isEqualTo(r.getContent());
        assertThat(last.getCitations()).isEqualTo(r.getCitations());
        assertThat(last.getSearches()).isEqualTo(r.getSearches());
        assertThat(last.getMetadata()).containsEntry("grounding.citation_basis", v.basis());
    }

    @ParameterizedTest
    @MethodSource("vendors")
    void theRecordedStreamEndsWithCitationsAndSearchesInItsFinalChunk(V v) {
        enqueue(v, "grounded-stream", "text/event-stream");
        List<LLMResponse> chunks = provider(v).chatStream(ask(Grounding.webSearch())).toList();
        String text = chunks.stream().map(LLMResponse::getContent).collect(Collectors.joining());
        LLMResponse last = chunks.get(chunks.size() - 1);
        assertThat(last.getCitations()).isNotEmpty();
        assertThat(last.getSearches().count()).isPositive();
        for (Citation c : last.getCitations())
            for (var s : c.spans()) assertThat(s.end()).isLessThanOrEqualTo(text.length());
        assertThat(chunks.subList(0, chunks.size() - 1))
                .allSatisfy(c -> assertThat(c.getCitations()).isEmpty());
    }

    // ── what is written (each shape is one the vendor really accepted: see the payloads) ─────

    @Test
    void perplexityGetsItsFiltersInsideTheSearchTool() throws Exception {
        V v = vendors().get(0);
        enqueue(v, "grounded-options", "application/json");
        provider(v)
                .chat(
                        ask(
                                Grounding.webSearch()
                                        .allowDomains("https://RBI.org.in/")
                                        .recency(Recency.YEAR)
                                        .country("IN")));
        JsonNode tool = sent().path("tools").get(0);
        assertThat(tool.toString())
                .isEqualTo(
                        "{\"type\":\"web_search\",\"filters\":{\"search_domain_filter\":[\"rbi.org.in\"],\"search_recency_filter\":\"year\"},\"user_location\":{\"country\":\"IN\"}}");
    }

    @Test
    void perplexityBlocksADomainWithAMinusInTheSameList() throws Exception {
        V v = vendors().get(0);
        enqueue(v, "grounded-block", "application/json");
        provider(v).chat(ask(Grounding.webSearch().blockDomains("economictimes.indiatimes.com")));
        assertThat(sent().path("tools").get(0).toString())
                .isEqualTo(
                        "{\"type\":\"web_search\",\"filters\":{\"search_domain_filter\":[\"-economictimes.indiatimes.com\"]}}");
    }

    @Test
    void openAiGetsDomainsCountryACapAndTheSourcesItIsAskedToInclude() throws Exception {
        V v = vendors().get(1);
        enqueue(v, "grounded-options", "application/json");
        provider(v)
                .chat(
                        ask(
                                Grounding.webSearch()
                                        .allowDomains("rbi.org.in")
                                        .country("IN")
                                        .maxSearches(2)));
        JsonNode body = sent();
        assertThat(body.path("tools").get(0).toString())
                .isEqualTo(
                        "{\"type\":\"web_search\",\"filters\":{\"allowed_domains\":[\"rbi.org.in\"]},\"user_location\":{\"country\":\"IN\",\"type\":\"approximate\"}}");
        assertThat(body.path("max_tool_calls").asInt()).isEqualTo(2);
        assertThat(body.path("include").toString())
                .isEqualTo("[\"web_search_call.action.sources\"]");
    }

    @Test
    void openAiBlocksADomainWithItsOwnList() throws Exception {
        V v = vendors().get(1);
        enqueue(v, "grounded-block", "application/json");
        provider(v).chat(ask(Grounding.webSearch().blockDomains("economictimes.indiatimes.com")));
        assertThat(sent().path("tools").get(0).toString())
                .isEqualTo(
                        "{\"type\":\"web_search\",\"filters\":{\"blocked_domains\":[\"economictimes.indiatimes.com\"]}}");
    }

    @Test
    void xaiNamesItsExcludedDomains() throws Exception {
        V v = vendors().get(2);
        enqueue(v, "grounded-block", "application/json");
        provider(v).chat(ask(Grounding.webSearch().blockDomains("economictimes.indiatimes.com")));
        assertThat(sent().path("tools").get(0).toString())
                .isEqualTo(
                        "{\"type\":\"web_search\",\"filters\":{\"excluded_domains\":[\"economictimes.indiatimes.com\"]}}");
    }

    @Test
    void theSearchToolSitsBesideTheCallersOwnTools() throws Exception {
        V v = vendors().get(1);
        enqueue(v, "grounded", "application/json");
        provider(v)
                .chat(
                        LLMRequest.builder()
                                .addUserMessage("x")
                                .tools(
                                        List.of(
                                                new ToolSpec(
                                                        "get_weather",
                                                        "Weather",
                                                        ToolSchema.object()
                                                                .string("city", "city", true)
                                                                .build())))
                                .grounding(Grounding.webSearch())
                                .build());
        JsonNode tools = sent().path("tools");
        assertThat(tools).hasSize(2);
        assertThat(tools.get(0).path("type").asText()).isEqualTo("function");
        assertThat(tools.get(1).path("type").asText()).isEqualTo("web_search");
    }

    @Test
    void aRequestWithoutGroundingGetsNoSearchTool() throws Exception {
        V v = vendors().get(1);
        enqueue(v, "grounded", "application/json");
        provider(v).chat(LLMRequest.builder().addUserMessage("x").build());
        JsonNode body = sent();
        assertThat(body.has("tools")).isFalse();
        assertThat(body.has("include")).isFalse();
    }

    // ── what each vendor can do, and what it refuses (R6) ────────────────────────────────────

    @Test
    void eachVendorDeclaresWhatItCanHonour() {
        assertThat(vendors().get(0).provider().apply(cfg()).groundingOptions())
                .containsExactlyInAnyOrder(
                        GroundingOption.ALLOW_DOMAINS,
                        GroundingOption.BLOCK_DOMAINS,
                        GroundingOption.RECENCY,
                        GroundingOption.COUNTRY);
        assertThat(vendors().get(1).provider().apply(cfg()).groundingOptions())
                .containsExactlyInAnyOrder(
                        GroundingOption.ALLOW_DOMAINS,
                        GroundingOption.BLOCK_DOMAINS,
                        GroundingOption.COUNTRY,
                        GroundingOption.MAX_SEARCHES);
        assertThat(vendors().get(2).provider().apply(cfg()).groundingOptions())
                .containsExactlyInAnyOrder(
                        GroundingOption.ALLOW_DOMAINS, GroundingOption.BLOCK_DOMAINS);
        for (V v : vendors())
            assertThat(v.provider().apply(cfg()).groundingSupport())
                    .isEqualTo(GroundingSupport.NATIVE);
    }

    private LLMConfig cfg() {
        return LLMConfig.builder().apiKey("k-1234567").defaultModel("m").build();
    }

    @Test
    void anOptionTheVendorHasNoWayToHonourIsRefusedBeforeAnyCallNamingBoth() {
        record Case(int vendor, Grounding.Builder grounding, String option) {}
        List<Case> cases =
                List.of(
                        new Case(0, Grounding.webSearch().maxSearches(2), "maxSearches"),
                        new Case(1, Grounding.webSearch().recency(Recency.WEEK), "recency"),
                        new Case(2, Grounding.webSearch().recency(Recency.WEEK), "recency"),
                        new Case(2, Grounding.webSearch().country("IN"), "country"),
                        new Case(2, Grounding.webSearch().maxSearches(1), "maxSearches"));
        for (Case c : cases) {
            V v = vendors().get(c.vendor());
            assertThatThrownBy(() -> new DefaultLLMClient(provider(v)).chat(ask(c.grounding())))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining(c.option())
                    .hasMessageContaining(v.provider().apply(cfg()).getProviderName());
        }
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void lenientDropsTheCapOnPerplexityAndSaysSo() throws Exception {
        V v = vendors().get(0);
        enqueue(v, "grounded", "application/json");
        LLMResponse r =
                new DefaultLLMClient(provider(v))
                        .chat(ask(Grounding.webSearch().lenient().maxSearches(2).country("IN")));
        assertThat(r.getMetadata()).containsEntry("grounding.ignored", List.of("maxSearches"));
        assertThat(sent().toString()).doesNotContain("max_tool_calls").doesNotContain("max_steps");
    }

    @Test
    void moreDomainsThanTheVendorAcceptsIsRefusedWithTheLimit() {
        V xai = vendors().get(2);
        assertThatThrownBy(
                        () ->
                                provider(xai)
                                        .chat(
                                                ask(
                                                        Grounding.webSearch()
                                                                .allowDomains(
                                                                        "a.com", "b.com", "c.com",
                                                                        "d.com", "e.com",
                                                                        "f.com"))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("at most 5");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void aDialectWithoutGroundingRefusesEvenWhenUsedDirectly() {
        ResponsesDialect none =
                ResponsesDialect.builder("plain")
                        .defaultBaseUrl("http://x/v1")
                        .keyRequired(false)
                        .build();
        LLMProvider p =
                new OpenAiResponsesProvider(none, LLMConfig.builder().defaultModel("m").build());
        assertThat(p.groundingSupport()).isEqualTo(GroundingSupport.NONE);
        assertThat(p.groundingOptions()).isEmpty();
        assertThatThrownBy(() -> p.chat(ask(Grounding.webSearch())))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("does not support grounding");
    }

    // ── indices, offsets and gaps in what a vendor sends ─────────────────────────────────────

    private static String answer(String text, String annotations, String usage) {
        return "{\"status\":\"completed\",\"model\":\"m\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":"
                + quote(text)
                + ",\"annotations\":"
                + annotations
                + "}]}],\"usage\":"
                + usage
                + "}";
    }

    private static String quote(String s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private LLMResponse openAiAnswering(String body) {
        server.enqueue(
                new MockResponse().setHeader("Content-Type", "application/json").setBody(body));
        return provider(vendors().get(1)).chat(ask(Grounding.webSearch()));
    }

    @Test
    void annotationIndicesCountCodePointsAndBecomeJavaOffsets() {
        // the emoji is one code point and two chars: the second link starts at char 15, not 14
        String text = "😀 yes [a](https://a.example) ok";
        int start = text.indexOf("[a]");
        int end = text.indexOf(')') + 1;
        int cpStart = text.codePointCount(0, start);
        int cpEnd = text.codePointCount(0, end);
        LLMResponse r =
                openAiAnswering(
                        answer(
                                text,
                                "[{\"type\":\"url_citation\",\"url\":\"https://a.example\",\"title\":\"A\",\"start_index\":"
                                        + cpStart
                                        + ",\"end_index\":"
                                        + cpEnd
                                        + "}]",
                                "{\"input_tokens\":1,\"output_tokens\":1}"));
        assertThat(start).isEqualTo(cpStart + 1);
        assertThat(r.getCitations().get(0).spans())
                .singleElement()
                .satisfies(
                        s -> {
                            assertThat(s.start()).isEqualTo(start);
                            assertThat(s.end()).isEqualTo(end);
                        });
    }

    @Test
    void aSourceCitedTwiceIsOneCitationWithTwoSpansAndARangeOutsideTheAnswerIsDropped() {
        String text = "one [1] two [1] three";
        LLMResponse r =
                openAiAnswering(
                        answer(
                                text,
                                "[{\"type\":\"url_citation\",\"url\":\"https://a.example\",\"title\":\"7\",\"start_index\":4,\"end_index\":7},"
                                        + "{\"type\":\"url_citation\",\"url\":\"https://b.example\",\"title\":\"B\",\"start_index\":0,\"end_index\":3},"
                                        + "{\"type\":\"url_citation\",\"url\":\"https://a.example\",\"start_index\":12,\"end_index\":15},"
                                        + "{\"type\":\"url_citation\",\"url\":\"https://b.example\",\"start_index\":10,\"end_index\":999}]",
                                "{\"input_tokens\":1,\"output_tokens\":1}"));
        assertThat(r.getCitations())
                .extracting(Citation::url)
                .containsExactly("https://a.example", "https://b.example");
        assertThat(r.getCitations()).extracting(Citation::index).containsExactly(1, 2);
        assertThat(r.getCitations().get(0).title()).isNull(); // "7" is a number, not a title
        assertThat(r.getCitations().get(0).spans()).hasSize(2);
        assertThat(r.getCitations().get(1).spans()).hasSize(1); // the range past the end is gone
        assertThat(r.getCitations().get(1).title()).isEqualTo("B");
    }

    @Test
    void aCountThatTheVendorDoesNotSayStaysUnknownAndNoSearchIsZero() {
        // OpenAI counts search items: an answer with none ran none
        LLMResponse none =
                openAiAnswering(answer("hello", "[]", "{\"input_tokens\":1,\"output_tokens\":1}"));
        assertThat(none.getSearches().count()).isZero();
        // Perplexity reports the count in its usage; when usage names no search, none ran
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                answer("hello", "[]", "{\"input_tokens\":1,\"output_tokens\":1}")));
        V p = vendors().get(0);
        assertThat(provider(p).chat(ask(Grounding.webSearch())).getSearches().count()).isZero();
        // no usage at all: nothing is known
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}]}"));
        assertThat(provider(p).chat(ask(Grounding.webSearch())).getSearches().count()).isNull();
    }

    @Test
    void groundingSupportIsDeclaredOnTheVendorKinds() {
        LLMConfig c = LLMConfig.builder().apiKey("k-1234567").build();
        assertThat(Vendor.PERPLEXITY.create(c, java.util.Map.of()).groundingSupport())
                .isEqualTo(GroundingSupport.NATIVE);
        for (Vendor v : List.of(Vendor.OPENAI, Vendor.XAI)) {
            assertThat(v.create(c, java.util.Map.of("api", "responses")).groundingSupport())
                    .as(v.kind() + " on the Responses API")
                    .isEqualTo(GroundingSupport.NATIVE);
            assertThat(v.create(c, java.util.Map.of()).groundingSupport())
                    .as(v.kind() + " on chat completions")
                    .isEqualTo(GroundingSupport.NONE);
        }
    }
}
