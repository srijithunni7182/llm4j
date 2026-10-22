package io.github.llm4j.eval.assertions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.eval.testing.AgentReplay;
import io.github.llm4j.eval.testing.ScriptedClient;
import io.github.llm4j.grounding.Citation;
import io.github.llm4j.grounding.Grounding;
import io.github.llm4j.grounding.SearchUsage;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * §6 (8g, acceptance A16): the citation assertions pass and fail correctly, on an agent's result
 * and on a single response, and a scripted client can return citations so a grounded workflow is
 * testable offline.
 */
class CitationAssertionsTest {

    private static Citation cite(int i, String url) {
        return new Citation(i, url, null, null, List.of());
    }

    private static AgentResult resultCiting(String... urls) {
        List<Citation> list = new java.util.ArrayList<>();
        for (String u : urls) list.add(cite(list.size() + 1, u));
        return AgentResult.builder().finalAnswer("a").completed(true).citations(list).build();
    }

    private static LLMResponse responseCiting(Integer searches, String... urls) {
        List<Citation> list = new java.util.ArrayList<>();
        for (String u : urls) list.add(cite(list.size() + 1, u));
        LLMResponse.Builder b = LLMResponse.builder().content("a").citations(list);
        if (searches != null) b.searches(SearchUsage.of(searches));
        return b.build();
    }

    // ── on an agent's result ─────────────────────────────────────────────────────────────────

    @Test
    void hasCitationsPassesWithOneAndFailsWithNone() {
        AgentAssertions.assertThat(resultCiting("https://who.int/a")).hasCitations();
        assertThatThrownBy(() -> AgentAssertions.assertThat(resultCiting()).hasCitations())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("at least <1>");
    }

    @Test
    void citesAtLeastCountsDistinctSources() {
        AgentResult r = resultCiting("https://a.example/x", "https://b.example/y");
        AgentAssertions.assertThat(r).citesAtLeast(2);
        assertThatThrownBy(() -> AgentAssertions.assertThat(r).citesAtLeast(3))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("<3>")
                .hasMessageContaining("https://a.example/x");
    }

    @Test
    void citesDomainMatchesTheHostAndItsSubdomainsOnly() {
        AgentResult r =
                resultCiting("https://www.WHO.int/news/item", "https://example.org/who.int");
        AgentAssertions.assertThat(r)
                .citesDomain("who.int")
                .citesDomain("https://WHO.int/anything");
        assertThatThrownBy(() -> AgentAssertions.assertThat(r).citesDomain("nih.gov"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("nih.gov");
        // a look-alike is not a subdomain, and a path mentioning the domain is not the host
        assertThatThrownBy(
                        () ->
                                AgentAssertions.assertThat(
                                                resultCiting(
                                                        "https://notwho.int/x",
                                                        "https://x.example/who.int"))
                                        .citesDomain("who.int"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void citesOnlyFromNeedsEverySourceOnAnAllowedDomainAndAtLeastOne() {
        AgentResult ok = resultCiting("https://www.who.int/a", "https://pubmed.nih.gov/b");
        AgentAssertions.assertThat(ok).citesOnlyFrom("who.int", "nih.gov");
        AgentResult mixed = resultCiting("https://who.int/a", "https://blog.example/c");
        assertThatThrownBy(
                        () -> AgentAssertions.assertThat(mixed).citesOnlyFrom("who.int", "nih.gov"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("https://blog.example/c")
                .hasMessageNotContaining("who.int/a]");
        assertThatThrownBy(
                        () -> AgentAssertions.assertThat(resultCiting()).citesOnlyFrom("who.int"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("cited nothing");
    }

    @Test
    void anAddressThatIsNotAUrlMatchesNothing() {
        AgentResult r = resultCiting("not a url", "");
        assertThatThrownBy(() -> AgentAssertions.assertThat(r).citesDomain("who.int"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> AgentAssertions.assertThat(r).citesOnlyFrom("who.int"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void usedSearchesIsReadFromTheRunsStatistics() {
        ScriptedClient model =
                new ScriptedClient()
                        .sources("https://who.int/a")
                        .searches(2)
                        .otherwise(ScriptedClient.reactFinal("done"));
        AgentResult r =
                ReActAgent.builder()
                        .llmClient(model)
                        .grounding(Grounding.webSearch())
                        .build()
                        .run("what is new?");
        AgentAssertions.assertThat(r).usedSearches(2).hasCitations().citesDomain("who.int");
        assertThatThrownBy(() -> AgentAssertions.assertThat(r).usedSearches(1))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("<1>")
                .hasMessageContaining("<2>");
    }

    // ── on a single response ────────────────────────────────────────────────────────────────

    @Test
    void theSameChecksWorkOnAResponse() {
        LLMResponse r = responseCiting(1, "https://www.who.int/a");
        LlmResponseAssertions.assertThat(r)
                .hasCitations()
                .citesAtLeast(1)
                .citesDomain("who.int")
                .citesOnlyFrom("who.int")
                .usedSearches(1);
        assertThatThrownBy(() -> LlmResponseAssertions.assertThat(r).citesAtLeast(2))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> LlmResponseAssertions.assertThat(r).citesDomain("nih.gov"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> LlmResponseAssertions.assertThat(r).citesOnlyFrom("nih.gov"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(
                        () ->
                                LlmResponseAssertions.assertThat(responseCiting(1))
                                        .citesOnlyFrom("nih.gov"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("cited nothing");
    }

    @Test
    void anUnknownSearchCountIsNotAtMostAnything() {
        assertThatThrownBy(
                        () ->
                                LlmResponseAssertions.assertThat(
                                                responseCiting(null, "https://a.example"))
                                        .usedSearches(5))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("no count");
        assertThatThrownBy(
                        () ->
                                LlmResponseAssertions.assertThat(
                                                responseCiting(3, "https://a.example"))
                                        .usedSearches(2))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("<3>");
        LlmResponseAssertions.assertThat(responseCiting(0)).usedSearches(0);
    }

    // ── scripted citations ──────────────────────────────────────────────────────────────────

    @Test
    void aScriptedClientReturnsSourcesOnlyToGroundedRequests() {
        ScriptedClient model =
                new ScriptedClient().sources("https://a.example", "https://b.example");
        LLMResponse plain =
                model.chat(io.github.llm4j.model.LLMRequest.builder().addUserMessage("hi").build());
        assertThat(plain.getCitations()).isEmpty();
        assertThat(plain.getSearches()).isNull();
        LLMResponse grounded =
                model.chat(
                        io.github.llm4j.model.LLMRequest.builder()
                                .addUserMessage("hi")
                                .grounding(Grounding.webSearch())
                                .build());
        assertThat(grounded.getCitations())
                .extracting(Citation::url)
                .containsExactly("https://a.example", "https://b.example");
        assertThat(grounded.getCitations()).extracting(Citation::index).containsExactly(1, 2);
        assertThat(grounded.getSearches().count()).isEqualTo(1);
        assertThat(model.groundedCalls()).isEqualTo(1);
    }

    @Test
    void anAgentThatDoesNotAskForGroundingGetsNoSources() {
        ScriptedClient model =
                new ScriptedClient()
                        .sources("https://a.example")
                        .otherwise(ScriptedClient.reactFinal("done"));
        AgentResult r = ReActAgent.builder().llmClient(model).build().run("x");
        assertThatThrownBy(() -> AgentAssertions.assertThat(r).hasCitations())
                .isInstanceOf(AssertionError.class);
        assertThat(model.groundedCalls()).isZero();
    }

    @Test
    void aReplayedRunKeepsItsSources(@TempDir Path dir) {
        AgentReplay replay = AgentReplay.at(dir);
        String key = AgentReplay.key("case-1");
        replay.run(key, () -> resultCiting("https://who.int/a", "https://nih.gov/b"));
        AgentResult again =
                replay.run(
                        key,
                        () -> {
                            throw new AssertionError("must not run again");
                        });
        AgentAssertions.assertThat(again).citesAtLeast(2).citesOnlyFrom("who.int", "nih.gov");
        assertThat(again.getCitations()).extracting(Citation::index).containsExactly(1, 2);
    }
}
