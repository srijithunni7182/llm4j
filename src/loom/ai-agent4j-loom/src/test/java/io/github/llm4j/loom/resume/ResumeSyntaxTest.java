package io.github.llm4j.loom.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.budget.Window;
import io.github.llm4j.loom.ast.BudgetDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RateLimitDef;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Verification plan V5.1–V5.5 (V5.6: the existing parser suites and documented examples). */
class ResumeSyntaxTest {

    static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    @Test
    void v5_1_rateLimitsBlock() {
        RateLimitDef rl = parse("rate_limits { on_limit: suspend max_wait: 24h max_resumes: 10 }").getRateLimits();
        assertThat(rl.getOnLimit()).isEqualTo(RateLimitDef.OnLimit.SUSPEND);
        assertThat(rl.getMaxWait()).isEqualTo(Duration.ofHours(24));
        assertThat(rl.getMaxResumes()).isEqualTo(10);
        assertThat(parse("rate_limits { on_limit: wait }").getRateLimits().getOnLimit()).isEqualTo(RateLimitDef.OnLimit.WAIT);
        assertThat(parse("rate_limits { on_limit: fail, }").getRateLimits().getMaxWait()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"30s,PT30S", "15m,PT15M", "6h,PT6H", "2d,P2D", "\"90s\",PT1M30S"})
    void v5_2_durations(String written, String expected) {
        RateLimitDef rl = parse("rate_limits { max_wait: " + written + " }").getRateLimits();
        assertThat(rl.getMaxWait()).isEqualTo(Duration.parse(expected.startsWith("P2D") ? "PT48H" : expected));
    }

    @Test
    void v5_3_windowedBudgetWithPolicy() {
        BudgetDef b = parse("budget { tokens: 100000 per day when_exhausted: suspend }").getBudget();
        assertThat(b.getTokens()).isEqualTo(100000);
        assertThat(b.getWindow()).isEqualTo(Window.DAY);
        assertThat(b.getWhenExhausted()).isEqualTo(BudgetDef.WhenExhausted.SUSPEND);
        BudgetDef calls = parse("budget { calls: 500 per hour, cost: \"$1\" per hour, when_exhausted: ask }").getBudget();
        assertThat(calls.getWindow()).isEqualTo(Window.HOUR);
        assertThat(calls.getWhenExhausted()).isEqualTo(BudgetDef.WhenExhausted.ASK);
        assertThat(parse("budget { tokens: 5 }").getBudget().getWhenExhausted()).isNull();
        BudgetDef agent = parse("agent A { model: \"m\" budget { tokens: 10 per minute per_call: 5 } }")
                .getAgents().get(0).getBudget();
        assertThat(agent.getWindow()).isEqualTo(Window.MINUTE);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "rate_limits { on_limit: later }|on_limit must be suspend, wait or fail",
            "rate_limits { max_wait: 0s }|max_wait must be positive",
            "rate_limits { max_wait: 5y }|Unknown duration unit",
            "rate_limits { max_wait: 1.5h }|whole number",
            "rate_limits { max_wait: \"soon\" }|must be a duration",
            "rate_limits { max_wait: 5 }|Expect a unit",
            "rate_limits { retries: 5 }|Unknown rate_limits field",
            "budget { tokens: 5 per fortnight }|per minute, per hour or per day",
            "budget { tokens: 5 per day calls: 3 per hour }|one window",
            "budget { tokens: 5 when_exhausted: pause }|when_exhausted must be stop, suspend or ask",
            "budget { tokens: 5 when_exhausted: suspend }|needs a budget that refills",
            "rate_limits { } rate_limits { }|Only one rate_limits block"})
    void v5_4_errorsNameTheLine(String source, String message) {
        assertThatThrownBy(() -> parse(source)).hasMessageContaining("line 1").hasMessageContaining(message);
    }

    @Test
    void v5_5_perAndRateLimitsStayOrdinaryNames() {
        LoomScript s = parse("""
                agent A { model: "m" }
                workflow Main() {
                    delegate "per day, rate_limits apply" to A -> per
                    delegate "{per}" to A -> rate_limits
                    delegate "x" to A -> when_exhausted budget 100 tokens
                }
                """);
        assertThat(s.getWorkflows().get(0).getStatements()).hasSize(3);
        assertThat(s.getRateLimits()).isNull();
    }

    @Test
    void importsMergeRateLimits() {
        LoomScript main = parse("agent A { model: \"m\" }");
        main.merge(parse("rate_limits { on_limit: fail }"));
        assertThat(main.getRateLimits().getOnLimit()).isEqualTo(RateLimitDef.OnLimit.FAIL);
    }
}
