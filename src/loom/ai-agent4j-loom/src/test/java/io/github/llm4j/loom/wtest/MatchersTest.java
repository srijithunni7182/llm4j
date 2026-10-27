package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R18 to R24 of spec 11: every matcher, positive and negative, and what a mismatch says. */
class MatchersTest {

    static final YAMLMapper YAML = new YAMLMapper();

    static JsonNode y(String text) throws Exception {
        return YAML.readTree(text);
    }

    static String bad(String expected, Object actual) throws Exception {
        return Matchers.mismatch(y(expected), actual);
    }

    @Test
    void aLiteralIsEqualToTheSameTextOrNumberOrBoolean() throws Exception {
        assertThat(bad("support", "support")).isNull();
        assertThat(bad("support", "billing"))
                .contains("expected support")
                .contains("got \"billing\"");
        assertThat(bad("3", 3)).isNull();
        assertThat(bad("3", "3")).isNull();
        assertThat(bad("3.0", 3)).isNull();
        assertThat(bad("3", 4)).contains("expected 3, got 4");
        assertThat(bad("true", true)).isNull();
        assertThat(bad("true", false)).isNotNull();
    }

    @Test
    void anyNeedsAValueAndAbsentNeedsNone() throws Exception {
        assertThat(bad("any", "x")).isNull();
        assertThat(bad("any", null)).contains("expected a value");
        assertThat(bad("absent", null)).isNull();
        assertThat(bad("absent", "x")).contains("expected nothing");
        assertThat(bad("{ absent: true }", null)).isNull();
    }

    @Test
    void containsAndMatchesLookInsideText() throws Exception {
        assertThat(bad("{ contains: ship }", "it shipped")).isNull();
        assertThat(bad("{ contains: ship }", "it was sent")).contains("containing \"ship\"");
        assertThat(bad("{ contains: ship }", null)).contains("got nothing");
        assertThat(bad("{ matches: '^order \\d+$' }", "order 42")).isNull();
        assertThat(bad("{ matches: '^order \\d+$' }", "order x")).contains("matching");
        assertThat(bad("{ matches: '(' }", "x")).contains("could not be used");
    }

    @Test
    void aPathologicalPatternGivesUpInsteadOfHangingTheSuite() throws Exception {
        String text = "a".repeat(40) + "!";
        long start = System.nanoTime();
        String problem = bad("{ matches: '(a+)+$' }", text);
        assertThat((System.nanoTime() - start) / 1_000_000_000).as("seconds").isLessThan(20);
        assertThat(problem).isNotNull();
    }

    @Test
    void oneOfAndTheNumericMatchersCompare() throws Exception {
        assertThat(bad("{ one_of: [a, b] }", "b")).isNull();
        assertThat(bad("{ one_of: [a, b] }", "c")).contains("one of");
        assertThat(bad("{ gt: 3 }", 4)).isNull();
        assertThat(bad("{ gt: 3 }", 3)).contains("expected gt 3, got 3");
        assertThat(bad("{ gte: 3 }", 3)).isNull();
        assertThat(bad("{ lt: 3 }", 2)).isNull();
        assertThat(bad("{ lt: 3 }", 3)).isNotNull();
        assertThat(bad("{ lte: 3 }", 3)).isNull();
        assertThat(bad("{ between: [1, 5] }", 5)).isNull();
        assertThat(bad("{ between: [1, 5] }", 6)).isNotNull();
        assertThat(bad("{ gt: 3 }", "not a number")).contains("expected a number");
    }

    @Test
    void typeAndNotWork() throws Exception {
        assertThat(bad("{ type: number }", 3)).isNull();
        assertThat(bad("{ type: number }", "12")).isNull();
        assertThat(bad("{ type: number }", "twelve")).contains("got text");
        assertThat(bad("{ type: object }", Map.of("a", 1))).isNull();
        assertThat(bad("{ type: list }", List.of(1))).isNull();
        assertThat(bad("{ not: { contains: x } }", "abc")).isNull();
        assertThat(bad("{ not: { contains: x } }", "xyz")).contains("anything but");
    }

    @Test
    void anObjectIsASubsetOfTheActualFieldsAndEachFieldMayBeAMatcher() throws Exception {
        Map<String, Object> actual = Map.of("team", "support", "route", "reply", "score", 9);
        assertThat(bad("{ team: support }", actual)).isNull();
        assertThat(bad("{ team: support, score: { gt: 5 } }", actual)).isNull();
        assertThat(bad("{ team: billing }", actual)).contains("team: expected billing");
        assertThat(bad("{ missing: x }", actual)).contains("missing: missing");
        assertThat(bad("{ team: support }", "{\"team\":\"support\",\"x\":1}")).isNull();
        assertThat(bad("{ team: support }", "plain text")).contains("expected an object");
    }

    @Test
    void aListIsComparedItemByItem() throws Exception {
        assertThat(bad("[a, b]", List.of("a", "b"))).isNull();
        assertThat(bad("[a, b]", List.of("a"))).contains("expected 2 items, got 1");
        assertThat(bad("[a, { contains: x }]", List.of("a", "xyz"))).isNull();
        assertThat(bad("[a, b]", "[\"a\",\"b\"]")).isNull();
    }

    @Test
    void longTextIsShownCutWithItsLengthAndAHash() throws Exception {
        String problem = bad("short", "x".repeat(1000));
        assertThat(problem).contains("1000 characters").contains("sha256");
        assertThat(problem.length()).isLessThan(400);
    }

    @Test
    void badMatchersAreCaughtWhenTheFileIsChecked() throws Exception {
        assertThat(Matchers.badMatcher(y("{ between: 3 }"))).isPresent();
        assertThat(Matchers.badMatcher(y("{ matches: '(' }"))).isPresent();
        assertThat(Matchers.badMatcher(y("{ contains: a }"))).isEmpty();
    }
}
