package io.github.llm4j.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.tools.CalculatorTool;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The value types of the grounding contract (spec 08 §4.2, R7, R8): builder rules, invariants and
 * what unknown means.
 */
class GroundingTypesTest {

    @Test
    void aBareGroundingHasNoOptions() {
        Grounding g = Grounding.webSearch().build();
        assertThat(g.maxSearches()).isNull();
        assertThat(g.allowDomains()).isEmpty();
        assertThat(g.blockDomains()).isEmpty();
        assertThat(g.recency()).isNull();
        assertThat(g.country()).isNull();
        assertThat(g.lenient()).isFalse();
        assertThat(g.fallback()).isEmpty();
        assertThat(g.options()).isEmpty();
    }

    @Test
    void everyOptionThatIsSetIsReportedByName() {
        Grounding g =
                Grounding.webSearch()
                        .maxSearches(3)
                        .allowDomains("who.int", "nih.gov")
                        .recency(Recency.YEAR)
                        .country("in")
                        .build();
        assertThat(g.maxSearches()).isEqualTo(3);
        assertThat(g.allowDomains()).containsExactly("who.int", "nih.gov");
        assertThat(g.recency()).isEqualTo(Recency.YEAR);
        assertThat(g.country()).isEqualTo("IN");
        assertThat(g.options())
                .containsExactlyInAnyOrder(
                        GroundingOption.MAX_SEARCHES,
                        GroundingOption.ALLOW_DOMAINS,
                        GroundingOption.RECENCY,
                        GroundingOption.COUNTRY);
        assertThat(Grounding.webSearch().blockDomains("reddit.com").build().options())
                .containsExactly(GroundingOption.BLOCK_DOMAINS);
    }

    @Test
    void allowedAndBlockedDomainsAreMutuallyExclusive() {
        assertThatThrownBy(
                        () ->
                                Grounding.webSearch()
                                        .allowDomains("who.int")
                                        .blockDomains("reddit.com")
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowDomains")
                .hasMessageContaining("blockDomains");
    }

    @Test
    void maxSearchesIsAtLeastOneAndTheCountryIsATwoLetterCode() {
        assertThatThrownBy(() -> Grounding.webSearch().maxSearches(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSearches");
        assertThatThrownBy(() -> Grounding.webSearch().country("IND"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("country");
        assertThatThrownBy(() -> Grounding.webSearch().country("1N"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void domainsAreBareHostsWithoutSchemeOrCase() {
        Grounding g =
                Grounding.webSearch()
                        .allowDomains("https://WHO.int/", "http://www.nih.gov/news", "who.int")
                        .build();
        assertThat(g.allowDomains()).containsExactly("who.int", "www.nih.gov/news");
        assertThatThrownBy(() -> Grounding.webSearch().allowDomains(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Grounding.webSearch().blockDomains("not a domain"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Grounding.webSearch().allowDomains((String[]) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aGroundingIsImmutableAndComparable() {
        Grounding a = Grounding.webSearch().maxSearches(2).allowDomains("who.int").build();
        Grounding b = Grounding.webSearch().maxSearches(2).allowDomains("who.int").build();
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a)
                .isNotEqualTo(Grounding.webSearch().maxSearches(3).allowDomains("who.int").build());
        assertThatThrownBy(() -> a.allowDomains().add("x.org"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(a.toString()).contains("maxSearches=2").contains("who.int");
    }

    @Test
    void lenientAndFallbackAreKept() {
        CalculatorTool tool = new CalculatorTool();
        Grounding g = Grounding.webSearch().lenient().fallback(tool).build();
        assertThat(g.lenient()).isTrue();
        assertThat(g.fallback()).containsSame(tool);
        // they are not options a provider has to honour
        assertThat(g.options()).isEmpty();
    }

    @Test
    void aCitationKeepsWhatTheVendorGaveAndNothingMore() {
        Citation c =
                new Citation(
                        1,
                        "https://who.int/news/item",
                        "WHO: sodium",
                        "Adults should eat less than 2 g.",
                        List.of(new Span(0, 12)));
        assertThat(c.index()).isEqualTo(1);
        assertThat(c.url()).isEqualTo("https://who.int/news/item");
        assertThat(c.spans()).containsExactly(new Span(0, 12));
        // missing parts stay missing, they are not made up
        Citation bare = new Citation(2, "https://a.example", null, null, null);
        assertThat(bare.title()).isNull();
        assertThat(bare.snippet()).isNull();
        assertThat(bare.spans()).isEmpty();
        // an empty or relative url is kept as given
        assertThat(new Citation(3, "", null, null, List.of()).url()).isEmpty();
        assertThat(new Citation(4, "/relative", null, null, List.of()).url())
                .isEqualTo("/relative");
        assertThatThrownBy(() -> new Citation(0, "u", null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index");
        assertThatThrownBy(() -> new Citation(1, null, null, null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> bare.spans().add(new Span(0, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aSpanIsAnOrderedCharacterRange() {
        assertThat(new Span(3, 3).length()).isZero();
        assertThat(new Span(2, 9).length()).isEqualTo(7);
        assertThatThrownBy(() -> new Span(-1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Span(5, 4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void spansLieWithinTheAnswerOrAreDropped() {
        String answer = "Sodium should stay under 2 g.";
        Citation c =
                new Citation(
                        1,
                        "https://who.int",
                        null,
                        null,
                        List.of(new Span(0, 6), new Span(7, 200), new Span(40, 50)));
        Citation clipped = c.withSpansWithin(answer);
        assertThat(clipped.spans()).containsExactly(new Span(0, 6));
        assertThat(c.withSpansWithin(answer).url()).isEqualTo(c.url());
    }

    @Test
    void searchUsageSaysUnknownIsUnknown() {
        SearchUsage known =
                new SearchUsage(2, List.of("who sodium 2026", "sodium guideline"), 0.005);
        assertThat(known.countKnown()).isTrue();
        assertThat(known.queries()).hasSize(2);
        SearchUsage unknown = new SearchUsage(null, null, null);
        assertThat(unknown.countKnown()).isFalse();
        assertThat(unknown.queries()).isEmpty();
        assertThat(unknown.estimatedCostUsd()).isNull();
        assertThatThrownBy(() -> new SearchUsage(-1, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchUsage(1, List.of(), -0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SearchUsage.of(3).count()).isEqualTo(3);
    }

    @Test
    void groundingSupportHasTwoValues() {
        assertThat(Set.of(GroundingSupport.values()))
                .containsExactlyInAnyOrder(GroundingSupport.NONE, GroundingSupport.NATIVE);
        assertThat(Recency.values())
                .containsExactly(Recency.DAY, Recency.WEEK, Recency.MONTH, Recency.YEAR);
    }
}
