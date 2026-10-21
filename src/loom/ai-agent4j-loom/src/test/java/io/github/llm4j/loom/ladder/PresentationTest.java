package io.github.llm4j.loom.ladder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.autonomy.expr.ExprParser;
import io.github.llm4j.loom.autonomy.expr.V;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** How values, durations and conditions are worded for people. */
@Tag("L8")
class PresentationTest {

    @Test
    void numbersAreTrimmedAndRoundedToFourPlaces() {
        assertThat(Show.number(3)).isEqualTo("3");
        assertThat(Show.number(0.5)).isEqualTo("0.5");
        assertThat(Show.number(0.123456)).isEqualTo("0.1235");
        assertThat(Show.number(2.0000001)).isEqualTo("2");
        assertThat(Show.number(-4)).isEqualTo("-4");
    }

    @Test
    void durationsUseTheLargestUnitThatDividesThemExactly() {
        assertThat(Show.duration(86_400_000L)).isEqualTo("1d");
        assertThat(Show.duration(2 * 3_600_000L)).isEqualTo("2h");
        assertThat(Show.duration(5 * 60_000L)).isEqualTo("5m");
        assertThat(Show.duration(30_000L)).isEqualTo("30s");
        assertThat(Show.duration(1500)).isEqualTo("1500ms");
        assertThat(Show.duration(0)).isEqualTo("0ms");
    }

    @Test
    void everyKindOfValueHasAShortForm() {
        assertThat(Show.value(V.UNDEFINED)).isEqualTo("unknown");
        assertThat(Show.value(V.bool(true))).isEqualTo("true");
        assertThat(Show.value(V.num(2.5))).isEqualTo("2.5");
        assertThat(Show.value(V.str("x"))).isEqualTo("\"x\"");
        assertThat(Show.value(V.dur(60_000))).isEqualTo("1m");
        assertThat(Show.value(V.ratio(9, 10))).startsWith("0.9 (10 cases, lower bound 0.5");
        assertThat(Show.value(new V.Lst(List.of(V.num(1), V.num(2))))).isEqualTo("2 values");
        assertThat(Show.value(V.cases(List.of()))).isEqualTo("0 cases");
        assertThat(Show.value(V.rec(Map.of()))).isEqualTo("Rec");
    }

    @Test
    void oneOuterPairOfBracketsIsRemovedOnlyWhenItWrapsTheWholeText() {
        assertThat(Show.unwrapped("(a >= 1)")).isEqualTo("a >= 1");
        assertThat(Show.unwrapped("(a) and (b)")).isEqualTo("(a) and (b)");
        assertThat(Show.unwrapped("a")).isEqualTo("a");
        assertThat(Show.unwrapped("()")).isEmpty();
        assertThat(Show.unwrapped("((a))")).isEqualTo("(a)");
    }

    private static String say(String e) {
        return ExprDescriber.describe(ExprParser.parse(e));
    }

    @Test
    void unitsAndComparisonsAreWordedInSingularAndPlural() {
        assertThat(say("history.span >= 1h"))
                .isEqualTo("the time the cases span is at least 1 hour");
        assertThat(say("history.span >= 90m"))
                .isEqualTo("the time the cases span is at least 90 minutes");
        assertThat(say("history.span >= 1m"))
                .isEqualTo("the time the cases span is at least 1 minute");
        assertThat(say("history.span >= 45s"))
                .isEqualTo("the time the cases span is at least 45 seconds");
        assertThat(say("history.span >= 250ms"))
                .isEqualTo("the time the cases span is at least 250 milliseconds");
        assertThat(say("history.count == 3")).isEqualTo("the number of cases is 3");
        assertThat(say("history.count != 3")).isEqualTo("the number of cases is not 3");
        assertThat(say("history.count > 3")).isEqualTo("the number of cases is more than 3");
        assertThat(say("history.count < 3")).isEqualTo("the number of cases is less than 3");
    }

    @Test
    void arithmeticSamplesAndQuantilesAreWorded() {
        assertThat(say("history.mean(x) >= a + b"))
                .isEqualTo("the average x of cases is at least a plus b");
        assertThat(say("history.mean(x) >= a - b"))
                .isEqualTo("the average x of cases is at least a minus b");
        assertThat(say("history.mean(x) >= a / 2"))
                .isEqualTo("the average x of cases is at least a divided by 2");
        assertThat(say("history.mean(x) >= -1"))
                .isEqualTo("the average x of cases is at least minus 1");
        assertThat(say("history.sum(x) > 3")).isEqualTo("the total x of cases is more than 3");
        assertThat(say("history.min(x) > 3")).isEqualTo("the lowest x of cases is more than 3");
        assertThat(say("history.max(x) > 3")).isEqualTo("the highest x of cases is more than 3");
        assertThat(say("history.quantile(x, 0.9) > 3"))
                .isEqualTo("the 0.9 quantile of x over cases is more than 3");
        assertThat(say("history.all(ok)")).isEqualTo("every case where ok");
        assertThat(say("history.last(5).all(ok)"))
                .isEqualTo("every case among the last 5 cases where ok");
        assertThat(say("history.last(5).any(ok)"))
                .isEqualTo("any case among the last 5 cases where ok");
        assertThat(say("history.between(30d, 7d).count >= 2"))
                .isEqualTo("the number of cases between 30 days and 7 days ago is at least 2");
        assertThat(say("history.by(tier).count().max() >= 2"))
                .isEqualTo("the highest, over each tier, of the number of cases is at least 2");
        assertThat(say("history.where(a).where(b).count >= 1"))
                .isEqualTo("the number of cases where a, where b is at least 1");
        assertThat(say("history.rate(ok).upper95 < 0.9"))
                .isEqualTo(
                        "an optimistic estimate of the share of cases where ok is less than 90%");
        assertThat(say("history.rate(ok).value < 0.9"))
                .isEqualTo("the share of cases where ok is less than 90%");
        assertThat(say("history.rate(ok).n >= 5"))
                .isEqualTo("the number of cases behind the share of cases where ok is at least 5");
        assertThat(say("history.by(tier).count()")).isEqualTo("the number of cases in each tier");
        assertThat(say("history.by(tier).rate(ok).min() >= 0.5"))
                .isEqualTo(
                        "the lowest, over each tier, of the share of cases where ok is at least 50%");
    }
}
