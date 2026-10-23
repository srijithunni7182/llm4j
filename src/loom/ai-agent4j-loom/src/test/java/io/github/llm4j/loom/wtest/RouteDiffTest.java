package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** R18, R19 of spec 11: a route mismatch says where the two routes part. */
class RouteDiffTest {

    static final List<String> RAN = List.of("classify Intent", "delegate Replier", "note");

    @Test
    void anOrderedSubsequenceMatches() {
        assertThat(RouteDiff.mismatch(List.of("classify Intent", "note"), RAN, false)).isNull();
        assertThat(RouteDiff.mismatch(List.of(), RAN, false)).isNull();
        assertThat(RouteDiff.mismatch(RAN, RAN, false)).isNull();
    }

    @Test
    void aStepThatDidNotHappenIsNamedWithTheStepBeforeIt() {
        String m = RouteDiff.mismatch(List.of("classify Intent", "delegate Escalator"), RAN, false);
        assertThat(m)
                .contains("step 2 of the expected route did not happen")
                .contains("\"delegate Escalator\"")
                .contains("after \"classify Intent\"")
                .contains("expected: classify Intent > >>delegate Escalator")
                .contains("actual  : classify Intent > >>delegate Replier > note");
    }

    @Test
    void theOrderMatters() {
        assertThat(RouteDiff.mismatch(List.of("note", "classify Intent"), RAN, false)).isNotNull();
    }

    @Test
    void exactMeansTheWholeRouteAndShowsTheFirstDivergence() {
        assertThat(RouteDiff.mismatch(RAN, RAN, true)).isNull();
        String m = RouteDiff.mismatch(List.of("classify Intent", "note"), RAN, true);
        assertThat(m)
                .contains("route differs at step 2")
                .contains("expected \"note\", got \"delegate Replier\"");
        assertThat(RouteDiff.mismatch(RAN.subList(0, 2), RAN, true))
                .contains("expected the route to end, got \"note\"");
        assertThat(RouteDiff.mismatch(RAN, RAN.subList(0, 2), true)).contains("got nothing more");
    }

    @Test
    void anEmptyActualRouteIsSaidSo() {
        assertThat(RouteDiff.mismatch(List.of("note"), List.of(), false)).contains("(no steps)");
    }
}
