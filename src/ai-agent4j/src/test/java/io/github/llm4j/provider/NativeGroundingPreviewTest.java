package io.github.llm4j.provider;

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
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolSchema;
import io.github.llm4j.model.ToolSpec;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.fixtures.Fixtures;
import io.github.llm4j.provider.google.GoogleProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Grounding for Anthropic and Google (8f; A9 to A11, A14; R7 to R9, R12). Most tests run on
 * fixtures written from the vendors' reference pages (their headers say so) and pin the mapping.
 * The tests named "Recorded" run on responses recorded from the real APIs on 2026-10-22: a grounded
 * answer for both vendors, a grounded stream for both, and an allowed-domain call for Anthropic.
 */
class NativeGroundingPreviewTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A_URL =
            "https://www.rbi.org.in/Scripts/BS_PressReleaseDisplay.aspx?prid=63839";
    private static final String B_URL =
            "https://www.business-standard.com/industry/banking/rbi-mpc-october-2026";

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

    private AnthropicProvider anthropic() {
        return new AnthropicProvider(
                LLMConfig.builder()
                        .apiKey("k-1234567")
                        .baseUrl(server.url("/").toString())
                        .defaultModel("claude-haiku-5-5")
                        .build());
    }

    private GoogleProvider google() {
        return new GoogleProvider(
                LLMConfig.builder()
                        .apiKey("k-1234567")
                        .baseUrl(server.url("/v1beta").toString())
                        .defaultModel("gemini-3.7-flash")
                        .build());
    }

    private void enqueue(String fixture, String type) {
        Fixtures f = Fixtures.load(fixture);
        assertThat(f.source()).isEqualTo("documentation-derived");
        server.enqueue(new MockResponse().setHeader("Content-Type", type).setBody(f.body()));
    }

    private static LLMRequest ask(Grounding.Builder g) {
        return LLMRequest.builder().addUserMessage("What is the repo rate?").grounding(g).build();
    }

    private JsonNode sent() throws Exception {
        return JSON.readTree(server.takeRequest().getBody().readUtf8());
    }

    private static String text(List<LLMResponse> chunks) {
        return chunks.stream().map(LLMResponse::getContent).collect(Collectors.joining());
    }

    // ── Anthropic ────────────────────────────────────────────────────────────────────────────

    @Test
    void claudeIsGivenTheServerSearchToolWithItsSettings() throws Exception {
        enqueue("anthropic/grounded.fixture", "application/json");
        anthropic()
                .chat(
                        ask(
                                Grounding.webSearch()
                                        .maxSearches(2)
                                        .blockDomains("example.com")
                                        .country("IN")));
        JsonNode tool = sent().path("tools").get(0);
        assertThat(tool.toString())
                .isEqualTo(
                        "{\"type\":\"web_search_20250305\",\"name\":\"web_search\",\"max_uses\":2,\"blocked_domains\":[\"example.com\"],\"user_location\":{\"type\":\"approximate\",\"country\":\"IN\"}}");
    }

    @Test
    void claudeAllowsDomainsAndKeepsTheCallersToolsFirst() throws Exception {
        enqueue("anthropic/grounded.fixture", "application/json");
        anthropic()
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
                                .grounding(Grounding.webSearch().allowDomains("rbi.org.in"))
                                .build());
        JsonNode body = sent();
        assertThat(body.path("tools")).hasSize(2);
        assertThat(body.path("tools").get(0).path("name").asText()).isEqualTo("get_weather");
        assertThat(body.path("tools").get(1).path("allowed_domains").toString())
                .isEqualTo("[\"rbi.org.in\"]");
        assertThat(body.path("tool_choice").path("type").asText()).isEqualTo("auto");
    }

    /** The one grounded Claude answer recorded from the real API (2026-10-22). */
    @Test
    void aRecordedClaudeAnswerIsReadIntoTextCitationsAndASearchCount() {
        Fixtures f = Fixtures.load("anthropic/grounded-recorded.fixture");
        assertThat(f.recorded()).isTrue();
        server.enqueue(
                new MockResponse().setHeader("Content-Type", "application/json").setBody(f.body()));

        LLMResponse r = anthropic().chat(ask(Grounding.webSearch()));

        assertThat(r.getContent()).startsWith("The RBI's policy repo rate is 5.50%").endsWith(".");
        assertThat(r.getCitations()).isNotEmpty();
        assertThat(r.getCitations().get(0).url()).startsWith("https://");
        assertThat(r.getCitations().get(0).spans()).isNotEmpty();
        assertThat(r.getSearches().count()).isEqualTo(1);
        assertThat(r.getSearches().queries()).containsExactly("RBI policy repo rate current");
    }

    /** The grounded Claude stream recorded from the real API (2026-10-22). */
    @Test
    void aRecordedClaudeStreamHasTheTextCitationsAndSearchAndNoToolCall() {
        assertThat(Fixtures.load("anthropic/grounded-stream-recorded.fixture").recorded()).isTrue();
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(
                                Fixtures.load("anthropic/grounded-stream-recorded.fixture")
                                        .body()));

        List<LLMResponse> chunks = anthropic().chatStream(ask(Grounding.webSearch())).toList();
        LLMResponse last = chunks.get(chunks.size() - 1);

        assertThat(text(chunks)).startsWith("The RBI's policy repo rate is 5.50%").endsWith(".");
        assertThat(chunks).allSatisfy(c -> assertThat(c.getToolCalls()).isEmpty());
        assertThat(last.getCitations()).isNotEmpty();
        assertThat(last.getCitations().get(0).url()).startsWith("https://");
        assertThat(last.getCitations().get(0).spans()).isNotEmpty();
        assertThat(last.getSearches().count()).isEqualTo(1);
    }

    /** A call with an allowed domain, recorded from the real API: the filter was accepted. */
    @Test
    void aRecordedAnswerFromAnAllowedDomainIsReadAndTheRequestCarriesTheFilter() throws Exception {
        assertThat(Fixtures.load("anthropic/grounded-domains-recorded.fixture").recorded())
                .isTrue();
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                Fixtures.load("anthropic/grounded-domains-recorded.fixture")
                                        .body()));

        LLMResponse r = anthropic().chat(ask(Grounding.webSearch().allowDomains("rbi.org.in")));

        assertThat(r.getContent()).isNotBlank();
        assertThat(r.getCitations()).isNotEmpty();
        assertThat(r.getCitations()).allSatisfy(c -> assertThat(c.url()).contains("rbi.org.in"));
        assertThat(r.getSearches().count()).isEqualTo(1);
        assertThat(sent().path("tools").get(0).path("allowed_domains").get(0).asText())
                .isEqualTo("rbi.org.in");
    }

    @Test
    void aClaudeAnswerHasCitationsSpannedByTheTextBlocksThatCarryThem() {
        enqueue("anthropic/grounded.fixture", "application/json");
        LLMResponse r = anthropic().chat(ask(Grounding.webSearch()));

        assertThat(r.getContent())
                .isEqualTo(
                        "I'll search for that.The policy repo rate is 5.50%, last changed on October 7, 2026.");
        assertThat(r.getCitations()).extracting(Citation::url).containsExactly(A_URL, B_URL);
        assertThat(r.getCitations()).extracting(Citation::index).containsExactly(1, 2);
        Citation a = r.getCitations().get(0);
        assertThat(a.title()).isEqualTo("RBI press release: Monetary Policy Statement");
        assertThat(a.snippet()).startsWith("the policy repo rate under the liquidity");
        assertThat(a.spans()).hasSize(2);
        assertThat(r.getContent().substring(a.spans().get(0).start(), a.spans().get(0).end()))
                .isEqualTo("The policy repo rate is 5.50%");
        assertThat(
                        r.getContent()
                                .substring(
                                        r.getCitations().get(1).spans().get(0).start(),
                                        r.getCitations().get(1).spans().get(0).end()))
                .isEqualTo(", last changed on October 7, 2026.");
        assertThat(r.getMetadata()).containsEntry("grounding.citation_basis", "cited");
        assertThat(r.getSearches().count()).isEqualTo(1);
        assertThat(r.getSearches().queries())
                .containsExactly("Reserve Bank of India repo rate October 2026");
        assertThat(r.getSearches().estimatedCostUsd()).isNull();
    }

    @Test
    void aClaudeStreamAgreesWithTheWholeAnswerAndShowsNoSpuriousToolCall() {
        enqueue("anthropic/grounded.fixture", "application/json");
        LLMResponse whole = anthropic().chat(ask(Grounding.webSearch()));
        enqueue("anthropic/grounded-stream.fixture", "text/event-stream");
        List<LLMResponse> chunks = anthropic().chatStream(ask(Grounding.webSearch())).toList();

        LLMResponse last = chunks.get(chunks.size() - 1);
        assertThat(text(chunks)).isEqualTo(whole.getContent());
        assertThat(last.getCitations()).isEqualTo(whole.getCitations());
        assertThat(last.getSearches()).isEqualTo(whole.getSearches());
        assertThat(last.getMetadata()).containsEntry("grounding.citation_basis", "cited");
        assertThat(chunks).allSatisfy(c -> assertThat(c.getToolCallDelta()).isNull());
        assertThat(last.getTokenUsage().getTotalTokens()).isPositive();
    }

    @Test
    void whenClaudeCitesNothingTheSearchResultsAreListedAsConsulted() throws Exception {
        JsonNode whole = JSON.readTree(Fixtures.load("anthropic/grounded.fixture").body());
        // drop the citations from the text blocks
        whole.path("content")
                .forEach(
                        b -> {
                            if (b instanceof com.fasterxml.jackson.databind.node.ObjectNode o)
                                o.remove("citations");
                        });
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(whole.toString()));
        LLMResponse r = anthropic().chat(ask(Grounding.webSearch()));
        assertThat(r.getCitations()).hasSize(3);
        assertThat(r.getCitations()).allSatisfy(c -> assertThat(c.spans()).isEmpty());
        assertThat(r.getMetadata()).containsEntry("grounding.citation_basis", "consulted");
    }

    @Test
    void theSearchCountFallsBackToTheSearchBlocksWhenUsageSaysNothing() throws Exception {
        JsonNode whole = JSON.readTree(Fixtures.load("anthropic/grounded.fixture").body());
        ((com.fasterxml.jackson.databind.node.ObjectNode) whole.path("usage"))
                .remove("server_tool_use");
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(whole.toString()));
        assertThat(anthropic().chat(ask(Grounding.webSearch())).getSearches().count()).isEqualTo(1);
    }

    @Test
    void aGroundedTurnThatEndsInAToolCallIsSentBackWithItsSearchBlocksUntouched() throws Exception {
        JsonNode whole = JSON.readTree(Fixtures.load("anthropic/grounded.fixture").body());
        com.fasterxml.jackson.databind.node.ObjectNode withCall =
                (com.fasterxml.jackson.databind.node.ObjectNode) whole;
        ((com.fasterxml.jackson.databind.node.ArrayNode) withCall.get("content"))
                .addObject()
                .put("type", "tool_use")
                .put("id", "toolu_01")
                .put("name", "get_weather")
                .putObject("input")
                .put("city", "Pune");
        withCall.put("stop_reason", "tool_use");
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(withCall.toString()));
        ToolSpec weather =
                new ToolSpec(
                        "get_weather",
                        "Weather",
                        ToolSchema.object().string("city", "city", true).build());
        LLMResponse first =
                anthropic()
                        .chat(
                                LLMRequest.builder()
                                        .addUserMessage("rate and weather?")
                                        .tools(List.of(weather))
                                        .grounding(Grounding.webSearch())
                                        .build());
        assertThat(first.getToolCalls()).hasSize(1);
        assertThat(first.getCitations()).isNotEmpty();

        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(Fixtures.load("anthropic/grounded.fixture").body()));
        anthropic()
                .chat(
                        LLMRequest.builder()
                                .addUserMessage("rate and weather?")
                                .addMessage(
                                        Message.assistantToolCalls(
                                                first.getContent(),
                                                first.getToolCalls(),
                                                first.getProviderData()))
                                .addMessage(Message.toolResult("toolu_01", "get_weather", "31C"))
                                .tools(List.of(weather))
                                .grounding(Grounding.webSearch())
                                .build());
        server.takeRequest(); // the first call
        JsonNode second = sent();
        JsonNode assistant = second.path("messages").get(1);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        assertThat(assistant.path("content"))
                .isEqualTo(withCall.path("content")); // every block, encrypted_content included
        assertThat(second.path("messages").get(2).path("content").get(0).path("type").asText())
                .isEqualTo("tool_result");
    }

    @Test
    void claudeCannotFilterByRecencyAndTheClientSaysSoBeforeTheCall() {
        assertThat(anthropic().groundingSupport()).isEqualTo(GroundingSupport.NATIVE);
        assertThat(anthropic().groundingOptions())
                .containsExactlyInAnyOrder(
                        GroundingOption.MAX_SEARCHES,
                        GroundingOption.ALLOW_DOMAINS,
                        GroundingOption.BLOCK_DOMAINS,
                        GroundingOption.COUNTRY);
        assertThatThrownBy(
                        () ->
                                new DefaultLLMClient(anthropic())
                                        .chat(ask(Grounding.webSearch().recency(Recency.WEEK))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("recency")
                .hasMessageContaining("anthropic");
        assertThat(server.getRequestCount()).isZero();
    }

    // ── Google ───────────────────────────────────────────────────────────────────────────────

    @Test
    void geminiGetsTheGoogleSearchToolAndRecencyIsAnIntervalEndingNow() throws Exception {
        enqueue("google/grounded.fixture", "application/json");
        google().chat(ask(Grounding.webSearch()));
        assertThat(sent().path("tools").toString()).isEqualTo("[{\"googleSearch\":{}}]");

        enqueue("google/grounded.fixture", "application/json");
        Instant before = Instant.now();
        google().chat(ask(Grounding.webSearch().recency(Recency.WEEK)));
        JsonNode range = sent().path("tools").get(0).path("googleSearch").path("timeRangeFilter");
        Instant end = Instant.parse(range.path("endTime").asText());
        Instant start = Instant.parse(range.path("startTime").asText());
        assertThat(end).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(Duration.between(start, end)).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void geminiSearchSitsBesideTheCallersFunctions() throws Exception {
        enqueue("google/grounded.fixture", "application/json");
        google().chat(
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
        assertThat(tools.get(0).has("functionDeclarations")).isTrue();
        assertThat(tools.get(1).has("googleSearch")).isTrue();
    }

    @Test
    void aGeminiAnswerHasCitationsWhoseSpansAreCharacterOffsetsConvertedFromBytes() {
        enqueue("google/grounded.fixture", "application/json");
        LLMResponse r = google().chat(ask(Grounding.webSearch()));

        String answer = r.getContent();
        assertThat(answer).contains("₹"); // a 3-byte character before the second segment
        assertThat(r.getCitations()).hasSize(3);
        assertThat(r.getCitations())
                .extracting(Citation::title)
                .containsExactly("rbi.org.in", "business-standard.com", "example.org");
        Citation first = r.getCitations().get(0);
        assertThat(first.spans()).hasSize(2);
        assertThat(answer.substring(first.spans().get(0).start(), first.spans().get(0).end()))
                .isEqualTo("The RBI's policy repo rate is 5.50%");
        assertThat(first.spans().get(1).end()).isEqualTo(answer.length()); // 90 bytes, 88 chars
        assertThat(r.getCitations().get(1).spans()).hasSize(1);
        assertThat(r.getCitations().get(2).spans()).isEmpty();
        assertThat(r.getMetadata()).containsEntry("grounding.citation_basis", "cited");
        assertThat(r.getSearches().count()).isEqualTo(2);
        assertThat(r.getSearches().queries())
                .containsExactly(
                        "RBI repo rate October 2026",
                        "Reserve Bank of India repo rate last changed");
    }

    @Test
    void aGeminiStreamAgreesWithTheWholeAnswer() {
        enqueue("google/grounded.fixture", "application/json");
        LLMResponse whole = google().chat(ask(Grounding.webSearch()));
        enqueue("google/grounded-stream.fixture", "text/event-stream");
        List<LLMResponse> chunks = google().chatStream(ask(Grounding.webSearch())).toList();
        LLMResponse last = chunks.get(chunks.size() - 1);
        assertThat(text(chunks)).isEqualTo(whole.getContent());
        assertThat(last.getCitations()).isEqualTo(whole.getCitations());
        assertThat(last.getSearches()).isEqualTo(whole.getSearches());
    }

    /** The grounded Gemini answer and stream recorded from the real API (2026-10-22). */
    @Test
    void aRecordedGeminiAnswerAndStreamAgree() {
        for (String f :
                List.of(
                        "google/grounded-recorded.fixture",
                        "google/grounded-stream-recorded.fixture")) {
            assertThat(Fixtures.load(f).recorded()).as(f).isTrue();
        }
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(Fixtures.load("google/grounded-recorded.fixture").body()));
        LLMResponse whole = google().chat(ask(Grounding.webSearch()));
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(Fixtures.load("google/grounded-stream-recorded.fixture").body()));
        List<LLMResponse> chunks = google().chatStream(ask(Grounding.webSearch())).toList();
        LLMResponse last = chunks.get(chunks.size() - 1);

        assertThat(whole.getContent())
                .startsWith("The Reserve Bank of India's current policy repo rate is 5.50%");
        assertThat(whole.getCitations()).hasSize(3);
        Citation first = whole.getCitations().get(0);
        assertThat(first.spans()).isNotEmpty();
        assertThat(
                        whole.getContent()
                                .substring(
                                        first.spans().get(0).start(), first.spans().get(0).end()))
                .startsWith("The Reserve Bank of India's current policy repo rate is 5.50%");
        assertThat(whole.getSearches().count()).isEqualTo(1);
        assertThat(whole.getSearches().queries())
                .containsExactly("current RBI policy repo rate October 2026");
        assertThat(text(chunks)).isEqualTo(whole.getContent());
        // two separate calls: Google puts a different redirect token in each url
        assertThat(last.getCitations())
                .extracting(Citation::title)
                .containsExactlyElementsOf(
                        whole.getCitations().stream().map(Citation::title).toList());
        assertThat(last.getCitations())
                .extracting(Citation::spans)
                .containsExactlyElementsOf(
                        whole.getCitations().stream().map(Citation::spans).toList());
        assertThat(last.getSearches().count()).isEqualTo(whole.getSearches().count());
    }

    @Test
    void aSegmentOutsideTheAnswerOrInsideACharacterIsDropped() throws Exception {
        JsonNode whole = JSON.readTree(Fixtures.load("google/grounded.fixture").body());
        com.fasterxml.jackson.databind.node.ArrayNode supports =
                (com.fasterxml.jackson.databind.node.ArrayNode)
                        whole.path("candidates")
                                .get(0)
                                .path("groundingMetadata")
                                .path("groundingSupports");
        supports.removeAll();
        com.fasterxml.jackson.databind.node.ObjectNode beyond = supports.addObject();
        beyond.putObject("segment").put("startIndex", 0).put("endIndex", 9999);
        beyond.putArray("groundingChunkIndices").add(0);
        com.fasterxml.jackson.databind.node.ObjectNode inside = supports.addObject();
        inside.putObject("segment")
                .put("startIndex", 0)
                .put("endIndex", 41); // inside the 3-byte character
        inside.putArray("groundingChunkIndices").add(1);
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(whole.toString()));
        LLMResponse r = google().chat(ask(Grounding.webSearch()));
        assertThat(r.getCitations()).hasSize(3);
        assertThat(r.getCitations()).allSatisfy(c -> assertThat(c.spans()).isEmpty());
    }

    @Test
    void anAnswerWithoutGroundingMetadataRanNoSearch() throws Exception {
        JsonNode whole = JSON.readTree(Fixtures.load("google/grounded.fixture").body());
        ((com.fasterxml.jackson.databind.node.ObjectNode) whole.path("candidates").get(0))
                .remove("groundingMetadata");
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(whole.toString()));
        LLMResponse r = google().chat(ask(Grounding.webSearch()));
        assertThat(r.getCitations()).isEmpty();
        assertThat(r.getSearches().count()).isZero();
        assertThat(r.getMetadata()).doesNotContainKey("grounding.citation_basis");
    }

    @Test
    void geminiOffersRecencyOnlyAndTheClientRefusesOrDropsTheRest() throws Exception {
        assertThat(google().groundingSupport()).isEqualTo(GroundingSupport.NATIVE);
        assertThat(google().groundingOptions()).containsExactly(GroundingOption.RECENCY);
        assertThatThrownBy(
                        () ->
                                new DefaultLLMClient(google())
                                        .chat(
                                                ask(
                                                        Grounding.webSearch()
                                                                .maxSearches(2)
                                                                .allowDomains("who.int"))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("maxSearches")
                .hasMessageContaining("allowDomains");
        assertThat(server.getRequestCount()).isZero();
        enqueue("google/grounded.fixture", "application/json");
        LLMResponse r =
                new DefaultLLMClient(google())
                        .chat(ask(Grounding.webSearch().lenient().maxSearches(2)));
        assertThat(r.getMetadata()).containsEntry("grounding.ignored", List.of("maxSearches"));
        assertThat(sent().toString()).doesNotContain("maxSearches");
    }

    @Test
    void aRequestWithoutGroundingIsAnswerableWithoutAnySearchFields() throws Exception {
        enqueue("google/grounded.fixture", "application/json");
        LLMResponse r = google().chat(LLMRequest.builder().addUserMessage("hi").build());
        assertThat(r.getCitations()).isEmpty();
        assertThat(r.getSearches()).isNull();
        assertThat(sent().has("tools")).isFalse();
        enqueue("anthropic/grounded.fixture", "application/json");
        LLMResponse a = anthropic().chat(LLMRequest.builder().addUserMessage("hi").build());
        assertThat(a.getCitations()).isEmpty();
        assertThat(a.getSearches()).isNull();
        assertThat(Map.of("sent", sent().has("tools"))).containsEntry("sent", false);
    }
}
