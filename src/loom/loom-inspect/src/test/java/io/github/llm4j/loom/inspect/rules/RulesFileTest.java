package io.github.llm4j.loom.inspect.rules;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.inspect.state.InspectRules;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The rules file: what it reads, what it refuses, and how it meets a script. The script never mentions any of it. */
class RulesFileTest {

    @Test
    void everyRuleIsReadFromBlocksWrittenOverSeveralLinesOrOne_withCommentsAndAnAllAgentsBlock() {
        RulesFile.Parsed p = RulesFile.parse("""
                # how to watch flow.loom
                Editor {
                    same_action: 5 times        # a comment
                    same_output: 4 times
                    tool_failures: 2 times
                    no_progress: 3 rounds on review.score
                }
                Scriptwriter { ungrounded_figures: warn at 1, high at 3 }
                * { paused_longer_than: 2m; same_action: 6 times }   // for everyone
                """);
        assertThat(p.problems()).isEmpty();
        assertThat(p.rules().get("Editor")).isEqualTo(new InspectRules(5, 4, 2, null, null, null, 3, "review.score"));
        assertThat(p.rules().get("Scriptwriter")).isEqualTo(new InspectRules(null, null, null, 1, 3, null, null, null));
        assertThat(p.rules().get("*")).isEqualTo(new InspectRules(6, null, null, null, null, 120L, null, null));
        assertThat(p.lines()).containsEntry("Editor", 2).containsEntry("*", 9);
    }

    @Test
    void durationsAreSecondsMinutesHoursOrDays() {
        assertThat(RulesFile.parse("* { paused_longer_than: 90s }").rules().get("*").pausedLongerThanSeconds()).isEqualTo(90L);
        assertThat(RulesFile.parse("* { paused_longer_than: 2h }").rules().get("*").pausedLongerThanSeconds()).isEqualTo(7200L);
        assertThat(RulesFile.parse("* { paused_longer_than: 1d }").rules().get("*").pausedLongerThanSeconds()).isEqualTo(86400L);
    }

    @Test
    void badInputIsRefusedWithLineAndWhatWasExpected() {
        assertThat(messages("Editor { same_action: 0 times }")).anyMatch(m -> m.contains("whole number from 1"));
        assertThat(messages("Editor { same_action: 4 }")).anyMatch(m -> m.contains("cannot read") && m.contains("same_action: 4 times"));
        assertThat(messages("Editor { no_progress: 3 rounds review.score }")).anyMatch(m -> m.contains("no_progress: 3 rounds on review.score"));
        assertThat(messages("Editor { ungrounded_figures: medium at 2 }")).anyMatch(m -> m.contains("warn at 2, high at 4"));
        assertThat(messages("Editor { ungrounded_figures: warn at 4, high at 4 }")).anyMatch(m -> m.contains("must be lower than"));
        assertThat(messages("Editor { paused_longer_than: soon }")).anyMatch(m -> m.contains("paused_longer_than: 90s"));
        assertThat(messages("Editor {\n same_action: 4 times\n")).anyMatch(m -> m.contains("not closed"));
        assertThat(messages("same_action: 4 times")).anyMatch(m -> m.contains("expected 'AgentName {'"));
        assertThat(messages("Editor { same_action: 2 times }\nEditor { same_action: 3 times }")).anyMatch(m -> m.contains("already a block for Editor"));
        assertThat(messages("* { no_progress: 3 rounds on review }")).anyMatch(m -> m.contains("one agent's loop"));
    }

    @Test
    void anUnknownRuleNamesTheNearestOne_andAnEmptyBlockIsOnlyAWarning() {
        assertThat(messages("Editor { same_actions: 4 times }")).anyMatch(m -> m.contains("unknown rule 'same_actions'") && m.contains("same_action") && m.contains("tool_failures"));
        RulesFile.Parsed empty = RulesFile.parse("Editor { }");
        assertThat(empty.ok()).isTrue();
        assertThat(empty.problems()).singleElement().satisfies(p -> assertThat(p.error()).isFalse());
        assertThat(empty.rules()).isEmpty();
    }

    @Test
    void theRulesFileSitsBesideTheScriptWithTheSameName() {
        assertThat(RulesFile.sidecarFor(Path.of("/flows/content.loom"))).isEqualTo(Path.of("/flows/content.inspect"));
        assertThat(RulesFile.sidecarFor(Path.of("flow"))).isEqualTo(Path.of("flow.inspect"));
    }

    // ---- against a script ----

    private static LoomScript script() {
        return new LoomParser(new Lexer("""
                agent Editor { model: "m" }
                agent Lead { model: "m" }
                workflow Main(topic) {
                    delegate "x {topic}" to Editor -> review
                    delegate "y {review}" to Lead -> verdict
                    note "{verdict}"
                }
                """).tokenize()).parseScript();
    }

    private static List<String> messages(String text) {
        return RulesFile.parse(text).problems().stream().map(RulesFile.Problem::message).toList();
    }

    private static List<String> against(String text) {
        return RulesCheck.check(script(), RulesFile.parse(text)).stream().map(RulesFile.Problem::toString).toList();
    }

    @Test
    void anAgentOrVariableThatExistsPasses_oneThatDoesNotIsAnErrorWithAGuess() {
        assertThat(against("Editor { no_progress: 3 rounds on review.score }\nLead { same_output: 3 times }\n* { same_action: 5 times }")).isEmpty();
        assertThat(against("Edtor { same_action: 2 times }")).singleElement().asString().contains("no agent named Edtor").contains("Editor");
        assertThat(against("Editor { no_progress: 3 rounds on reveiw.score }")).singleElement().asString().contains("no workflow variable named reveiw").contains("review");
        assertThat(against("Editor { no_progress: 3 rounds on topic }")).isEmpty(); // a parameter is a variable too
    }

    @Test
    void rulesWrittenFromTheStructureReadBackAsTheSameRules_starFirst_andSpansInTheirLargestUnit() {
        Map<String, InspectRules> rules = new java.util.LinkedHashMap<>();
        rules.put("Editor", new InspectRules(3, null, null, null, null, null, 2, "review.fix"));
        rules.put("*", new InspectRules(null, null, 5, null, null, 5400L, null, null));
        rules.put("Scriptwriter", new InspectRules(null, 2, null, 2, 4, 120L, null, null));
        rules.put("Onlywarn", new InspectRules(null, null, null, 3, null, null, null, null));
        String text = RulesFile.write(rules);
        assertThat(text.indexOf("* {")).isLessThan(text.indexOf("Editor {"));
        assertThat(text).contains("paused_longer_than: 90m").contains("paused_longer_than: 2m").contains("ungrounded_figures: warn at 2, high at 4")
                .contains("ungrounded_figures: warn at 3\n").contains("no_progress: 2 rounds on review.fix");
        RulesFile.Parsed back = RulesFile.parse(text);
        assertThat(back.ok()).isTrue();
        assertThat(back.rules()).isEqualTo(rules);
    }

    @Test
    void anAgentWithNothingSetIsLeftOut() {
        assertThat(RulesFile.write(Map.of("Editor", new InspectRules(null, null, null, null, null, null, null, null)))).doesNotContain("Editor");
    }
}
