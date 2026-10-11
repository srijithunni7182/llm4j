package io.github.llm4j.loom.autonomy.expr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("L1")
class VTest {

    @Test
    void undefinedIsNeverTrue() {
        assertThat(V.UNDEFINED.isUndefined()).isTrue();
        assertThat(V.isTrue(V.UNDEFINED)).isFalse();
        assertThat(V.isTrue(V.bool(true))).isTrue();
        assertThat(V.isTrue(V.bool(false))).isFalse();
        assertThat(V.isTrue(V.num(1))).isFalse();
    }

    @Test
    void recordFieldsAreLookedUpAndAMissingOneIsUndefined() {
        V.Rec r = V.rec(Map.of("a", V.num(1), "b", V.rec(Map.of("c", V.str("x")))));
        assertThat(r.get("a")).isEqualTo(V.num(1));
        assertThat(((V.Rec) r.get("b")).get("c")).isEqualTo(V.str("x"));
        assertThat(r.get("missing")).isEqualTo(V.UNDEFINED);
    }

    @Test
    void ratioBuildsItsIntervalFromCounts() {
        V.Rat r = (V.Rat) V.ratio(9, 10);
        assertThat(r.value()).isEqualTo(0.9);
        assertThat(r.n()).isEqualTo(10);
        assertThat(r.lower95()).isLessThan(0.9);
        assertThat(r.upper95()).isGreaterThan(0.9);
        assertThat(V.ratio(0, 0)).isEqualTo(V.UNDEFINED);
    }

    @Test
    void numericViewAcceptsNumbersAndRatiosAndDurationsOnly() {
        assertThat(V.asNumber(V.num(2))).hasValue(2.0);
        assertThat(V.asNumber(V.ratio(1, 2))).hasValue(0.5);
        assertThat(V.asNumber(V.dur(1500))).hasValue(1500.0);
        assertThat(V.asNumber(V.str("2"))).isEmpty();
        assertThat(V.asNumber(V.UNDEFINED)).isEmpty();
        assertThat(V.cases(List.of()).cases()).isEmpty();
    }
}
