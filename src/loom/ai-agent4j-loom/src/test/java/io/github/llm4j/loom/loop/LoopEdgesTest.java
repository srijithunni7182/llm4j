package io.github.llm4j.loom.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The edges of the small parts of the production loop: ages, strategies, the config, the caps. */
class LoopEdgesTest {

    @TempDir Path dir;

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    @Test
    void sinceTakesDaysHoursWeeksAndDates() {
        assertThat(Candidates.since("14d", NOW)).isEqualTo(NOW.minusSeconds(14 * 86_400L));
        assertThat(Candidates.since("12h", NOW)).isEqualTo(NOW.minusSeconds(12 * 3600L));
        assertThat(Candidates.since("2w", NOW)).isEqualTo(NOW.minusSeconds(14 * 86_400L));
        assertThat(Candidates.since("2026-10-01", NOW))
                .isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(Candidates.since("2026-10-01T10:00:00Z", NOW))
                .isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(Candidates.since(" ", NOW)).isNull();
        assertThat(Candidates.since(null, NOW)).isNull();
    }

    @Test
    void everySamplingStrategyHasAWordAndAnUnknownOneNamesTheChoices() {
        for (String w : new String[] {"random", "recent", "stratified", "failures-first"})
            assertThat(Sampler.Strategy.of(w)).isNotNull();
        assertThatThrownBy(() -> Sampler.Strategy.of("lucky"))
                .hasMessageContaining("random, recent, stratified or failures-first");
    }

    @Test
    void theConfigRefusesWhatItDoesNotKnow() throws Exception {
        Path f = dir.resolve("gate.properties");
        Files.writeString(f, "chekc = true\n");
        assertThatThrownBy(() -> GateConfig.load(f)).hasMessageContaining("unknown key \"chekc\"");
        Files.writeString(f, "review = fail-on:urgent\n");
        assertThatThrownBy(() -> GateConfig.load(f)).hasMessageContaining("is not fail-on:high");
        Files.writeString(f, "check = maybe\n");
        assertThatThrownBy(() -> GateConfig.load(f)).hasMessageContaining("is not true or false");
        Files.writeString(
                f,
                "review = off\ncheck = false\ntest.min-coverage = 80\nregress.store = s\nregress.since = 7d\nregress.limit = 5\nregress.fail-on = any\neval = true\n");
        GateConfig c = GateConfig.load(f);
        assertThat(c.reviewFailOn()).isEqualTo("off");
        assertThat(c.check()).isFalse();
        assertThat(c.minCoverage()).isEqualTo(80);
        assertThat(c.limit()).isEqualTo(5);
        assertThat(c.eval()).isTrue();
    }

    @Test
    void policyAgesAreWrittenInDaysHoursOrWeeks() throws Exception {
        Path script = dir.resolve("main.loom");
        Files.writeString(script, "workflow Main() { return \"x\" }\n");
        for (String age : new String[] {"30d", "12h", "2w"}) {
            Files.writeString(dir.resolve("capture.properties"), "max-age = " + age + "\n");
            assertThat(Policy.load(script).maxAge()).isNotNull();
        }
        Files.writeString(dir.resolve("capture.properties"), "max-age = soon\n");
        assertThatThrownBy(() -> Policy.load(script)).hasMessageContaining("number and d, h or w");
    }

    @Test
    void theCapStopsOnTokensOrOnMoneyAndTellsWhatIsLeft() {
        Spent tokens = new Spent(100L, null);
        tokens.add(60, 1, null);
        assertThat(tokens.exhausted()).isFalse();
        assertThat(tokens.remainingTokens()).isEqualTo(40);
        assertThat(tokens.remainingCost()).isNull();
        tokens.add(60, 1, null);
        assertThat(tokens.exhausted()).isTrue();
        assertThat(tokens.remainingTokens()).isZero();
        assertThat(tokens.cost()).isNull(); // a call that could not be priced

        Spent money = new Spent(null, new BigDecimal("0.10"));
        money.add(10, 1, new BigDecimal("0.06"));
        assertThat(money.exhausted()).isFalse();
        assertThat(money.remainingCost()).isEqualByComparingTo("0.04");
        money.add(10, 1, new BigDecimal("0.06"));
        assertThat(money.exhausted()).isTrue();
        assertThat(money.remainingCost()).isEqualByComparingTo("0");
        assertThat(money.cost()).isEqualByComparingTo("0.12");
        assertThat(money.calls()).isEqualTo(2);
        assertThat(money.maxCost()).isEqualByComparingTo("0.10");
        assertThat(money.maxTokens()).isNull();
    }

    @Test
    void retiringOrApprovingAFileThatIsNotACaptureIsRefused() throws Exception {
        Path f = dir.resolve("plain.test.yaml");
        Files.writeString(
                f,
                "script: main.loom\nworkflow: Main\ncases:\n- name: x\n  expect: { final: completed }\n");
        assertThatThrownBy(() -> Approval.retire(f, "me", null))
                .hasMessageContaining("was not made by weave capture");
        assertThatThrownBy(() -> Approval.approve(f, "me", null, Map.of()))
                .hasMessageContaining("was not made by weave capture");
    }
}
