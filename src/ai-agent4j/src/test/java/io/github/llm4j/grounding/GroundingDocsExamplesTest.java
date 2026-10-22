package io.github.llm4j.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.budget.Budget;
import io.github.llm4j.budget.BudgetExceeded;
import io.github.llm4j.budget.BudgetedLLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.fixtures.Fixtures;
import io.github.llm4j.provider.openai.OpenAiResponsesProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/**
 * The code samples in wiki/Web-Search-Grounding.md, compiled and run against stand-in providers.
 * Change a sample here and regenerate the page together (a test fails if they drift).
 */
class GroundingDocsExamplesTest {

    /** A provider with native grounding that answers with two sources. */
    private static LLMProvider searching(Set<GroundingOption> options) {
        return new Stub("searching", GroundingSupport.NATIVE, options, false, List.of());
    }

    private static final class Stub implements LLMProvider {
        final String name;
        final GroundingSupport support;
        final Set<GroundingOption> options;
        final boolean tools;
        final List<LLMResponse> replies = new ArrayList<>();

        Stub(
                String name,
                GroundingSupport support,
                Set<GroundingOption> options,
                boolean tools,
                List<LLMResponse> replies) {
            this.name = name;
            this.support = support;
            this.options = options;
            this.tools = tools;
            this.replies.addAll(replies);
        }

        @Override
        public LLMResponse chat(LLMRequest request) {
            if (!replies.isEmpty()) return replies.remove(0);
            String text = "Under 2 g of sodium a day [1].";
            return LLMResponse.builder()
                    .content(text)
                    .model("stub")
                    .tokenUsage(40, 12, 52)
                    .finishReason("stop")
                    .citations(
                            List.of(
                                    new Citation(
                                            1,
                                            "https://www.who.int/news/item/sodium",
                                            "WHO sodium guidance",
                                            null,
                                            List.of(new Span(text.indexOf("[1]"), text.length() - 1)))))
                    .searches(SearchUsage.of(1))
                    .build();
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            return Stream.of(chat(request));
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

    @Test
    void askAndRead() {
        LLMClient client = new DefaultLLMClient(searching(Set.of(GroundingOption.ALLOW_DOMAINS, GroundingOption.MAX_SEARCHES)));
        // --- sample: ask
        LLMRequest request =
                LLMRequest.builder()
                        .addUserMessage("What is the daily sodium limit?")
                        .grounding(Grounding.webSearch().maxSearches(2).allowDomains("who.int", "nih.gov"))
                        .build();
        LLMResponse answer = client.chat(request);
        // --- end sample
        // --- sample: read
        for (Citation c : answer.getCitations()) {
            System.out.println("[" + c.index() + "] " + c.title() + " " + c.url());
        }
        Integer searches = answer.getSearches().count(); // null when the vendor did not say
        // --- end sample
        assertThat(searches).isEqualTo(1);
        assertThat(answer.getCitations()).hasSize(1);
        assertThat(answer.getCitations().get(0).spans()).hasSize(1);
    }

    @Test
    void refusal() {
        LLMClient client = new DefaultLLMClient(new Stub("sarvam-chat", GroundingSupport.NONE, Set.of(), false, List.of()));
        // --- sample: refuse
        try {
            client.chat(LLMRequest.builder().addUserMessage("News?").grounding(Grounding.webSearch()).build());
        } catch (InvalidRequestException e) {
            System.out.println(e.getMessage()); // names the provider: it does not support grounding
        }
        // --- end sample
        assertThatThrownBy(() -> client.chat(LLMRequest.builder().addUserMessage("News?").grounding(Grounding.webSearch()).build()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("sarvam-chat");
    }

    @Test
    void lenient() {
        LLMClient client = new DefaultLLMClient(searching(Set.of(GroundingOption.RECENCY)));
        // --- sample: lenient
        LLMResponse r =
                client.chat(
                        LLMRequest.builder()
                                .addUserMessage("What changed this week?")
                                .grounding(Grounding.webSearch().recency(Recency.WEEK).maxSearches(2).lenient())
                                .build());
        Object dropped = r.getMetadata().get("grounding.ignored"); // [maxSearches]
        // --- end sample
        assertThat(dropped).isEqualTo(List.of("maxSearches"));
    }

    @Test
    void fallback() {
        Tool webSearch =
                new Tool() {
                    @Override
                    public String getName() {
                        return "WebSearch";
                    }

                    @Override
                    public String getDescription() {
                        return "Searches the web";
                    }

                    @Override
                    public String execute(Map<String, Object> args) {
                        return "WHO guidance: https://www.who.int/news/item/sodium";
                    }
                };
        LLMProvider provider =
                new Stub(
                        "ollama",
                        GroundingSupport.NONE,
                        Set.of(),
                        true,
                        List.of(
                                LLMResponse.builder()
                                        .finishReason("tool_calls")
                                        .toolCalls(List.of(new ToolCall("c1", "WebSearch", Map.of("query", "who sodium"))))
                                        .build(),
                                LLMResponse.builder().content("Under 2 g a day.").finishReason("stop").build()));
        LLMClient client = new DefaultLLMClient(provider);
        // --- sample: fallback
        LLMResponse r =
                client.chat(
                        LLMRequest.builder()
                                .addUserMessage("What is the daily sodium limit?")
                                .grounding(Grounding.webSearch().fallback(webSearch))
                                .build());
        List<Citation> sources = r.getCitations(); // the URLs the tool returned
        // --- end sample
        assertThat(sources).extracting(Citation::url).containsExactly("https://www.who.int/news/item/sodium");
        assertThat(r.getSearches().count()).isEqualTo(1);
    }

    @Test
    void budget() {
        LLMClient model = new DefaultLLMClient(searching(Set.of()));
        // --- sample: budget
        Budget budget = Budget.builder().name("run").searches(1).build();
        LLMClient metered = BudgetedLLMClient.builder(model).budget(budget).build();
        LLMRequest ask = LLMRequest.builder().addUserMessage("News?").grounding(Grounding.webSearch()).build();
        metered.chat(ask); // runs one search
        try {
            metered.chat(ask); // refused before it is made: no search left
        } catch (BudgetExceeded e) {
            System.out.println(e.dimension()); // SEARCHES
        }
        // --- end sample
        assertThat(budget.spent().searches()).isEqualTo(1);
        assertThatThrownBy(() -> metered.chat(ask)).isInstanceOf(BudgetExceeded.class);
    }

    @Test
    void perplexity() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            // a response recorded from the real API on 2026-10-22
            server.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(Fixtures.load("perplexity-agent/grounded.fixture").body()));
            LLMConfig config =
                    LLMConfig.builder()
                            .apiKey("key-for-the-examples")
                            .baseUrl(server.url("/v1").toString())
                            .defaultModel("openai/gpt-6-luna")
                            .build();
            // --- sample: perplexity
            LLMClient perplexity = new DefaultLLMClient(OpenAiResponsesProvider.perplexity(config));
            LLMResponse r =
                    perplexity.chat(
                            LLMRequest.builder()
                                    .addUserMessage("What is the RBI's current policy repo rate?")
                                    .grounding(Grounding.webSearch().recency(Recency.YEAR).country("IN"))
                                    .build());
            String basis = (String) r.getMetadata().get("grounding.citation_basis"); // consulted
            Double cost = r.getSearches().estimatedCostUsd(); // 0.0025: Perplexity reports it
            // --- end sample
            assertThat(basis).isEqualTo("consulted");
            assertThat(cost).isEqualTo(0.0025);
            assertThat(r.getCitations()).hasSize(3);
            String sent = server.takeRequest().getBody().readUtf8();
            assertThat(sent).contains("\"type\":\"web_search\"").contains("\"search_recency_filter\":\"year\"");
        }
    }

    @Test
    void thePageShowsEverySampleVerbatim() throws Exception {
        String source = Files.readString(Path.of("src/test/java/io/github/llm4j/grounding/GroundingDocsExamplesTest.java"));
        String page = Files.readString(Path.of("wiki/Web-Search-Grounding.md"));
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("// --- sample: ([^\\n]+)\\n(.*?)\\n\\s*// --- end sample", java.util.regex.Pattern.DOTALL).matcher(source);
        int n = 0;
        while (m.find()) {
            n++;
            assertThat(page).as("the page shows the '" + m.group(1) + "' sample").contains(m.group(2).stripIndent().strip());
        }
        assertThat(n).isEqualTo(7);
    }
}
