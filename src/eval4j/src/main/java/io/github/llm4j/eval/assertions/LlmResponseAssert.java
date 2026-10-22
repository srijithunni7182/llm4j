package io.github.llm4j.eval.assertions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.eval.export.EvalChecks;
import io.github.llm4j.eval.export.MetricRef;
import io.github.llm4j.model.LLMResponse;
import java.util.regex.Pattern;
import org.assertj.core.api.AbstractObjectAssert;

/**
 * AssertJ custom assertion for a raw {@link LLMResponse} — a single LLM call that isn't behind a
 * {@code ReActAgent}. Obtain one via {@link LlmResponseAssertions#assertThat(LLMResponse)}.
 */
public class LlmResponseAssert extends AbstractObjectAssert<LlmResponseAssert, LLMResponse> {

    private static final MetricRef M_TOKENS =
            MetricRef.measured(
                    "token-budget", "Token budget", "prompts", "answers", "efficiency", "tokens");

    private static final MetricRef M_HASCONTENTCONTAINING =
            MetricRef.assertion(
                    "content-contains", "Content contains", "prompts", "answers", "correctness");
    private static final MetricRef M_HASCONTENTMATCHING =
            MetricRef.assertion(
                    "content-matches", "Content matches", "prompts", "answers", "correctness");
    private static final MetricRef M_HASFINISHREASON =
            MetricRef.assertion(
                    "finish-reason", "Finish reason", "prompts", "answers", "reliability");
    private static final MetricRef M_HASVALIDJSON =
            MetricRef.assertion(
                    "valid-json", "Valid JSON output", "prompts", "answers", "reliability");

    private static final MetricRef M_HASCITATIONS =
            MetricRef.assertion(
                    "has-citations", "Has citations", "prompts", "answers", "grounding");
    private static final MetricRef M_CITESATLEAST =
            MetricRef.assertion(
                    "cites-at-least", "Cites at least", "prompts", "answers", "grounding");
    private static final MetricRef M_CITESDOMAIN =
            MetricRef.assertion(
                    "cites-domain", "Cites a domain", "prompts", "answers", "grounding");
    private static final MetricRef M_CITESONLYFROM =
            MetricRef.assertion(
                    "cites-only-from", "Cites only from", "prompts", "answers", "grounding");
    private static final MetricRef M_USEDSEARCHES =
            MetricRef.measured(
                    "searches", "Web searches", "prompts", "answers", "efficiency", "searches");

    public LlmResponseAssert(LLMResponse actual) {
        super(actual, LlmResponseAssert.class);
    }

    /** The answer carries at least one source (see {@link LLMResponse#getCitations()}). */
    public LlmResponseAssert hasCitations() {
        return atLeast(M_HASCITATIONS, 1);
    }

    /** At least {@code n} sources. */
    public LlmResponseAssert citesAtLeast(int n) {
        return atLeast(M_CITESATLEAST, n);
    }

    private LlmResponseAssert atLeast(MetricRef metric, int n) {
        EvalChecks.check(
                metric,
                () -> {
                    isNotNull();
                    if (actual.getCitations().size() < n) {
                        failWithMessage(
                                "Expected the response to cite at least <%s> sources but it cited <%s>: %s",
                                n,
                                actual.getCitations().size(),
                                CitationChecks.urls(actual.getCitations()));
                    }
                });
        return this;
    }

    /**
     * Some source is on {@code domain}: its host is that domain or a subdomain ({@code www.who.int}
     * counts for {@code who.int}).
     */
    public LlmResponseAssert citesDomain(String domain) {
        EvalChecks.check(
                M_CITESDOMAIN,
                () -> {
                    isNotNull();
                    if (actual.getCitations().stream()
                            .noneMatch(c -> CitationChecks.matches(c, domain))) {
                        failWithMessage(
                                "Expected a source on <%s> but the sources were %s",
                                domain, CitationChecks.urls(actual.getCitations()));
                    }
                });
        return this;
    }

    /**
     * There is at least one source, and every one is on one of {@code domains}. A response with no
     * sources fails: "only from" is not satisfied by saying nothing.
     */
    public LlmResponseAssert citesOnlyFrom(String... domains) {
        java.util.List<String> allowed = java.util.List.of(domains);
        EvalChecks.check(
                M_CITESONLYFROM,
                () -> {
                    isNotNull();
                    if (actual.getCitations().isEmpty()) {
                        failWithMessage(
                                "Expected sources only from %s but the response cited nothing",
                                allowed);
                        return;
                    }
                    java.util.List<String> outside =
                            actual.getCitations().stream()
                                    .filter(c -> !CitationChecks.anyMatches(c, allowed))
                                    .map(io.github.llm4j.grounding.Citation::url)
                                    .toList();
                    if (!outside.isEmpty()) {
                        failWithMessage(
                                "Expected sources only from %s but found %s", allowed, outside);
                    }
                });
        return this;
    }

    /**
     * The call ran at most {@code atMost} web searches. A vendor that reported no count (or a
     * response that was not grounded) fails: an unknown number is not "at most".
     */
    public LlmResponseAssert usedSearches(int atMost) {
        EvalChecks.checkMeasured(
                M_USEDSEARCHES,
                actual == null || actual.getSearches() == null || !actual.getSearches().countKnown()
                        ? null
                        : (double) actual.getSearches().count(),
                "searches",
                (double) atMost,
                () -> {
                    isNotNull();
                    io.github.llm4j.grounding.SearchUsage u = actual.getSearches();
                    if (u == null || !u.countKnown()) {
                        failWithMessage(
                                "Expected at most <%s> web searches but the response reports no count",
                                atMost);
                        return;
                    }
                    if (u.count() > atMost) {
                        failWithMessage(
                                "Expected at most <%s> web searches but the call used <%s>",
                                atMost, u.count());
                    }
                });
        return this;
    }

    public LlmResponseAssert hasContentContaining(String expectedSubstring) {
        EvalChecks.check(
                M_HASCONTENTCONTAINING,
                () -> {
                    isNotNull();
                    assertThat(actual.getContent())
                            .as("content of LLM response")
                            .containsIgnoringCase(expectedSubstring);
                });
        return this;
    }

    public LlmResponseAssert hasContentMatching(Pattern pattern) {
        EvalChecks.check(
                M_HASCONTENTMATCHING,
                () -> {
                    isNotNull();
                    String content = actual.getContent();
                    if (content == null || !pattern.matcher(content).find()) {
                        failWithMessage(
                                "Expected response content to match pattern <%s> but was <%s>",
                                pattern, content);
                    }
                });
        return this;
    }

    public LlmResponseAssert hasFinishReason(LLMResponse.FinishReason expected) {
        EvalChecks.check(
                M_HASFINISHREASON,
                () -> {
                    isNotNull();
                    if (actual.getFinishReason() != expected) {
                        failWithMessage(
                                "Expected finish reason <%s> but was <%s>",
                                expected, actual.getFinishReason());
                    }
                });
        return this;
    }

    public LlmResponseAssert usesFewerTokensThan(int maxTotalTokens) {
        EvalChecks.checkMeasured(
                M_TOKENS,
                actual == null
                        ? null
                        : (double)
                                (actual.getTokenUsage() == null
                                        ? 0
                                        : actual.getTokenUsage().getTotalTokens()),
                "tokens",
                (double) maxTotalTokens,
                () -> {
                    isNotNull();
                    if (actual.getTokenUsage() == null) {
                        failWithMessage(
                                "Expected response to report token usage under <%s> but no TokenUsage was present",
                                maxTotalTokens);
                        return;
                    }
                    int actualTokens = actual.getTokenUsage().getTotalTokens();
                    if (actualTokens >= maxTotalTokens) {
                        failWithMessage(
                                "Expected response to use fewer than <%s> total tokens but used <%s>",
                                maxTotalTokens, actualTokens);
                    }
                });
        return this;
    }

    public LlmResponseAssert hasValidJson(Class<?> shape) {
        EvalChecks.check(
                M_HASVALIDJSON,
                () -> {
                    isNotNull();
                    try {
                        new ObjectMapper().readValue(actual.getContent(), shape);
                    } catch (Exception e) {
                        failWithMessage(
                                "Expected response content to be valid JSON matching <%s> but parsing failed: %s",
                                shape.getSimpleName(), e.getMessage());
                    }
                });
        return this;
    }
}
