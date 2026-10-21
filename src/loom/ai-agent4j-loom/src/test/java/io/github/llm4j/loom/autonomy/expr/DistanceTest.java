package io.github.llm4j.loom.autonomy.expr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code distance(a, b)}: how far a person moved a proposal, from 0 (untouched) to 1 (nothing
 * left).
 */
@Tag("L1")
class DistanceTest {

    private static double d(V a, V b) {
        V v = Distance.of(a, b);
        assertThat(v).isInstanceOf(V.Num.class);
        return ((V.Num) v).value();
    }

    @Test
    void identicalValuesAreZeroAndEmptyAgainstTextIsOne() {
        assertThat(d(V.str("same"), V.str("same"))).isZero();
        assertThat(d(V.str(""), V.str(""))).isZero();
        assertThat(d(V.str(""), V.str("abc"))).isEqualTo(1.0);
        assertThat(d(V.str("abc"), V.str(""))).isEqualTo(1.0);
    }

    @Test
    void textIsTheEditDistanceOverTheLongerLength() {
        assertThat(d(V.str("kitten"), V.str("sitting"))).isCloseTo(3.0 / 7, Offset.offset(1e-9));
        assertThat(d(V.str("abcd"), V.str("abXd"))).isCloseTo(0.25, Offset.offset(1e-9));
        assertThat(d(V.str("abc"), V.str("xyz"))).isEqualTo(1.0);
    }

    @Test
    void itIsSymmetricAndBoundedAndHandlesUnicode() {
        String[] samples = {
            "",
            "a",
            "héllo wörld",
            "日本語のテキスト",
            "the quick brown fox",
            "the quick brown cat",
            "emoji 😀 test"
        };
        for (String a : samples) {
            for (String b : samples) {
                double x = d(V.str(a), V.str(b));
                assertThat(x).isBetween(0.0, 1.0);
                assertThat(x).isEqualTo(d(V.str(b), V.str(a)));
                if (a.equals(b)) assertThat(x).isZero();
            }
        }
        assertThat(d(V.str("日本語"), V.str("日本人"))).isCloseTo(1.0 / 3, Offset.offset(1e-9));
    }

    @Test
    void structuredValuesAreTheShareOfLeafFieldsThatDiffer() {
        V a =
                V.rec(
                        Map.of(
                                "title",
                                V.str("Hi"),
                                "tags",
                                V.rec(Map.of("x", V.num(1), "y", V.num(2))),
                                "ok",
                                V.bool(true)));
        V same =
                V.rec(
                        Map.of(
                                "title",
                                V.str("Hi"),
                                "tags",
                                V.rec(Map.of("x", V.num(1), "y", V.num(2))),
                                "ok",
                                V.bool(true)));
        V changed =
                V.rec(
                        Map.of(
                                "title",
                                V.str("Ho"),
                                "tags",
                                V.rec(Map.of("x", V.num(1), "y", V.num(2))),
                                "ok",
                                V.bool(true)));
        V missing =
                V.rec(
                        Map.of(
                                "title",
                                V.str("Hi"),
                                "tags",
                                V.rec(Map.of("x", V.num(1))),
                                "ok",
                                V.bool(true)));
        assertThat(d(a, same)).isZero();
        assertThat(d(a, changed)).isCloseTo(0.25, Offset.offset(1e-9));
        assertThat(d(a, missing)).isCloseTo(0.25, Offset.offset(1e-9));
        assertThat(d(V.rec(Map.of()), V.rec(Map.of()))).isZero();
    }

    @Test
    void numbersAndBooleansAreZeroOrOne() {
        assertThat(d(V.num(3), V.num(3))).isZero();
        assertThat(d(V.num(3), V.num(4))).isEqualTo(1.0);
        assertThat(d(V.bool(true), V.bool(false))).isEqualTo(1.0);
    }

    @Test
    void mismatchedOrUndefinedInputsAreUndefined() {
        assertThat(Distance.of(V.str("a"), V.num(1))).isEqualTo(V.UNDEFINED);
        assertThat(Distance.of(V.UNDEFINED, V.str("a"))).isEqualTo(V.UNDEFINED);
        assertThat(Distance.of(V.str("a"), V.UNDEFINED)).isEqualTo(V.UNDEFINED);
        assertThat(Distance.of(V.str("a"), V.rec(Map.of()))).isEqualTo(V.UNDEFINED);
    }

    @Test
    @Timeout(20)
    void aLargeDocumentWithASmallEditIsFastAndSmall() {
        String base = "lorem ipsum dolor sit amet ".repeat(40_000); // about 1 MB
        String edited = base.substring(0, 500_000) + "CHANGED" + base.substring(500_007);
        double x = d(V.str(base), V.str(edited));
        assertThat(x).isBetween(0.0, 0.001);
    }

    @Test
    @Timeout(20)
    void twoLargeUnrelatedDocumentsAreUndefinedNotAHang() {
        String a = "alpha beta gamma ".repeat(60_000);
        String b = "zulu yankee xray ".repeat(60_000);
        assertThat(Distance.of(V.str(a), V.str(b))).isEqualTo(V.UNDEFINED);
    }
}
