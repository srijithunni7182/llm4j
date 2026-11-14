package io.github.llm4j.eval.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Phase 35, D17: what one pass cost, with its interval, in the run bundle's summary. */
class CostPerPassTest {

    @Test
    void theFigureIsTheCostOverThePassesWithAnIntervalFromThePassRate() {
        Map<String, Object> m = EvalRun.costPerPass(1.0, 5, 10);
        assertThat((double) m.get("usd")).isCloseTo(0.2, within(1e-9));
        double low = (double) m.get("lowUsd");
        double high = (double) m.get("highUsd");
        assertThat(low).isLessThan(0.2);
        assertThat(high).isGreaterThan(0.2);
        assertThat(low).isCloseTo(0.1 / 0.7634, within(1e-3));
        assertThat(m).containsEntry("passed", 5).containsEntry("judged", 10);
    }

    @Test
    void noPassHasNoFigureAndNoUpperBoundAndNothingJudgedHasNothing() {
        Map<String, Object> none = EvalRun.costPerPass(1.0, 0, 4);
        assertThat(none).doesNotContainKeys("usd", "highUsd").containsKey("lowUsd");
        assertThat(EvalRun.costPerPass(1.0, 0, 0)).isNull();
        Map<String, Object> all = EvalRun.costPerPass(1.0, 4, 4);
        assertThat((double) all.get("usd")).isCloseTo(0.25, within(1e-9));
    }
}
