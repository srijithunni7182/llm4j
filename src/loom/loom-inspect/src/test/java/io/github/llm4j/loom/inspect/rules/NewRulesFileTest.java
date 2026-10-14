package io.github.llm4j.loom.inspect.rules;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.inspect.state.InspectRules;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R12, R13: the rules about context, truncation, compaction, tool success and latency, in the same sidecar grammar. */
class NewRulesFileTest {

    private static List<String> messages(String text) {
        return RulesFile.parse(text).problems().stream().map(RulesFile.Problem::toString).toList();
    }

    @Test
    void everyNewRuleIsRead() {
        RulesFile.Parsed p = RulesFile.parse("""
                Researcher {
                    context_nearly_full: warn at 80%, high at 92%
                    truncated_answers: 2 times
                    compaction_thrash: 5 in 12 calls
                    tool_success_rate: below 60% over 20 calls
                    latency_spike: 3x p50
                }
                """);
        assertThat(p.problems()).isEmpty();
        InspectRules.Telemetry t = p.rules().get("Researcher").telemetry();
        assertThat(t).isEqualTo(new InspectRules.Telemetry(0.80, 0.92, 2, 5, 12, 0.60, 20, 3.0));
    }

    @Test
    void rulesLeftOutStayNull_andOldRulesStillReadAsBefore() {
        RulesFile.Parsed p = RulesFile.parse("Editor { same_action: 5 times; latency_spike: 2.5x p50 }\nWriter { same_output: 2 times }");
        assertThat(p.problems()).isEmpty();
        assertThat(p.rules().get("Editor").sameAction()).isEqualTo(5);
        assertThat(p.rules().get("Editor").telemetry()).isEqualTo(new InspectRules.Telemetry(null, null, null, null, null, null, null, 2.5));
        assertThat(p.rules().get("Writer").telemetry()).isNull();
        assertThat(p.rules().get("Writer")).isEqualTo(new InspectRules(null, 2, null, null, null, null, null, null));
    }

    @Test
    void eitherLevelAloneIsFine_andTheStarBlockTakesThem() {
        assertThat(RulesFile.parse("* { context_nearly_full: warn at 70% }").rules().get("*").telemetry().contextWarn()).isEqualTo(0.70);
        assertThat(RulesFile.parse("* { context_nearly_full: high at 90% }").rules().get("*").telemetry().contextHigh()).isEqualTo(0.90);
    }

    @Test
    void badValuesAreRefusedWithAnExampleOrAReason() {
        assertThat(messages("A { context_nearly_full: warn at 95%, high at 90% }")).anyMatch(m -> m.contains("must be lower than"));
        assertThat(messages("A { context_nearly_full: warn at 85% }\nB { context_nearly_full: warn at 0% }")).anyMatch(m -> m.contains("between 1% and 100%"));
        assertThat(messages("A { context_nearly_full: warn at 120% }")).anyMatch(m -> m.contains("between 1% and 100%"));
        assertThat(messages("A { context_nearly_full: 85% }")).anyMatch(m -> m.contains("warn at 85%, high at 95%"));
        assertThat(messages("A { truncated_answers: 0 times }")).anyMatch(m -> m.contains("whole number from 1"));
        assertThat(messages("A { compaction_thrash: 5 in 3 calls }")).anyMatch(m -> m.contains("cannot be more compactions than calls") || m.contains("5 in 12 calls"));
        assertThat(messages("A { compaction_thrash: 5 calls }")).anyMatch(m -> m.contains("compaction_thrash: 4 in 10 calls"));
        assertThat(messages("A { tool_success_rate: below 100% over 10 calls }")).anyMatch(m -> m.contains("between 1% and 99%"));
        assertThat(messages("A { tool_success_rate: below 70% over 3 calls }")).anyMatch(m -> m.contains("at least 5"));
        assertThat(messages("A { tool_success_rate: 70% }")).anyMatch(m -> m.contains("tool_success_rate: below 70% over 10 calls"));
        assertThat(messages("A { latency_spike: 1x p50 }")).anyMatch(m -> m.contains("more than 1"));
        assertThat(messages("A { latency_spike: slow }")).anyMatch(m -> m.contains("latency_spike: 5x p50"));
    }

    @Test
    void aMisspeltNewRuleNamesTheNearestOne() {
        assertThat(messages("A { context_nearly_ful: warn at 80% }")).anyMatch(m -> m.contains("unknown rule 'context_nearly_ful'") && m.contains("context_nearly_full"));
        assertThat(messages("A { tool_success_rat: below 70% over 10 calls }")).anyMatch(m -> m.contains("tool_success_rate"));
    }

    @Test
    void theNewRulesAreListedAmongTheRules() {
        assertThat(RulesFile.RULES).contains("context_nearly_full", "truncated_answers", "compaction_thrash", "tool_success_rate", "latency_spike");
    }

    @Test
    void writingThenReadingGivesTheSameRules() {
        Map<String, InspectRules> rules = Map.of(
                "Researcher", new InspectRules(3, null, null, null, null, null, null, null, new InspectRules.Telemetry(0.8, 0.9, 2, 5, 12, 0.6, 20, 3.0)),
                "*", new InspectRules(null, null, null, null, null, null, null, null, new InspectRules.Telemetry(null, 0.97, null, null, null, 0.5, 10, null)));
        String text = RulesFile.write(rules);
        assertThat(text).contains("context_nearly_full: warn at 80%, high at 90%").contains("tool_success_rate: below 60% over 20 calls").contains("latency_spike: 3x p50");
        RulesFile.Parsed back = RulesFile.parse(text);
        assertThat(back.problems()).isEmpty();
        assertThat(back.rules()).isEqualTo(rules);
    }

    @Test
    void aBlockWithOnlyNewRulesIsNotEmpty() {
        assertThat(RulesFile.parse("A { truncated_answers: 2 times }").problems()).isEmpty();
    }

    @Test
    void anUnknownAgentIsStillReportedAgainstTheScript() {
        LoomScript script = new LoomParser(new Lexer("agent Editor { model: \"m\" }\nworkflow Main() { delegate \"x\" to Editor -> out }\n").tokenize()).parseScript();
        RulesFile.Parsed p = RulesFile.parse("Edtor { latency_spike: 3x p50 }");
        assertThat(RulesCheck.check(script, p)).anyMatch(x -> x.message().contains("no agent named Edtor") && x.message().contains("Editor"));
    }
}
