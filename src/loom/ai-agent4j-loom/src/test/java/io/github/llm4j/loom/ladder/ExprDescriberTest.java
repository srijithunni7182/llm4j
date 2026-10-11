package io.github.llm4j.loom.ladder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.autonomy.expr.ExprParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A rung's condition read back as an English sentence, from the parsed condition and not from its text. */
@Tag("L8")
class ExprDescriberTest {

    private static String say(String expr) {
        return ExprDescriber.describe(ExprParser.parse(expr));
    }

    @Test
    void countsAndSpans() {
        assertThat(say("history.count >= 100")).isEqualTo("the number of cases is at least 100");
        assertThat(say("history.span >= 14d")).isEqualTo("the time the cases span is at least 14 days");
        assertThat(say("history.since(1d).count >= 50")).isEqualTo("the number of cases of the last 1 day is at least 50");
        assertThat(say("history.last(50).count(dangerous) >= 2")).isEqualTo("the number of the last 50 cases where dangerous is at least 2");
        assertThat(say("history.count(reversed) == 0")).isEqualTo("the number of cases where reversed is 0");
    }

    @Test
    void sharesAreShownAsPercentagesAndTheCautiousEstimateIsSaidSo() {
        assertThat(say("history.rate(agrees) >= 0.9")).isEqualTo("the share of cases where agrees is at least 90%");
        assertThat(say("history.rate(agrees).lower95 >= 0.97")).isEqualTo("a cautious estimate of the share of cases where agrees is at least 97%");
        assertThat(say("history.last(100).rate(agrees) < 0.92")).isEqualTo("the share of the last 100 cases where agrees is less than 92%");
        assertThat(say("history.where(careful).rate(untouched) >= 80%")).isEqualTo("the share of cases where careful, where untouched is at least 80%");
    }

    @Test
    void averagesMediansAndFilters() {
        assertThat(say("history.median(edit_size) <= 10%")).isEqualTo("the median edit size of cases is at most 10%");
        assertThat(say("history.last(10).mean(traffic) >= 0.8 * baseline_views")).isEqualTo("the average traffic of the last 10 cases is at least 0.8 times baseline views");
        assertThat(say("history.where(careful).count >= 6")).isEqualTo("the number of cases where careful is at least 6");
        assertThat(say("history.where(amount > 200).count")).isEqualTo("the number of cases where amount is more than 200");
    }

    @Test
    void combinationsAndTheCase() {
        assertThat(say("case.amount > 200 or case.topic in [\"legal\", \"pricing\"]")).isEqualTo("the case's amount is more than 200, or the case's topic is one of \"legal\", \"pricing\"");
        assertThat(say("history.count >= 5 and not history.any(rejected)")).isEqualTo("the number of cases is at least 5, and not any case where rejected");
        assertThat(say("history.by(tier).rate(agrees).min() >= 0.6")).isEqualTo("the lowest, over each tier, of the share of cases where agrees is at least 60%");
    }

    @Test
    void anythingItDoesNotKnowIsShownAsWritten() {
        assertThat(say("distance(c.proposal.value, c.response.final) < 0.1")).isEqualTo("how far c.proposal.value is from c.response.final is less than 0.1");
        assertThat(say("foo(1, 2) == 3")).isEqualTo("foo(1, 2) is 3");
    }
}
