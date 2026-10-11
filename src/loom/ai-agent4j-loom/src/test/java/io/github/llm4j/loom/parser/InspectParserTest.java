package io.github.llm4j.loom.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.InspectDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.lexer.Lexer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code inspect { }} inside an agent: what it reads, what it refuses, and what {@code weave check} says about it. */
class InspectParserTest {

    private static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    private static InspectDef inspectOf(String body) {
        return parse("agent Editor {\n model: \"m\"\n inspect {\n" + body + "\n }\n}\nworkflow W() { delegate \"x\" to Editor -> review }").getAgents().get(0).getInspect();
    }

    @Test
    void everyRuleIsRead() {
        InspectDef in = inspectOf("""
                same_action: 5 times
                same_output: 4 times
                tool_failures: 2 times
                no_progress: 3 rounds on review.score
                ungrounded_figures: warn at 1, high at 3
                paused_longer_than: 2m
                """);
        assertThat(in.getSameAction()).isEqualTo(5);
        assertThat(in.getSameOutput()).isEqualTo(4);
        assertThat(in.getToolFailures()).isEqualTo(2);
        assertThat(in.getNoProgressRounds()).isEqualTo(3);
        assertThat(in.getNoProgressVariable()).isEqualTo("review.score");
        assertThat(in.getUngroundedWarn()).isEqualTo(1);
        assertThat(in.getUngroundedHigh()).isEqualTo(3);
        assertThat(in.getPausedLongerThan()).isEqualTo(Duration.ofMinutes(2));
        assertThat(in.isEmpty()).isFalse();
    }

    @Test
    void aRuleLeftOutIsNullSoTheConsoleKeepsItsDefault_andEitherUngroundedLevelAloneIsFine() {
        InspectDef in = inspectOf("ungrounded_figures: high at 6");
        assertThat(in.getUngroundedHigh()).isEqualTo(6);
        assertThat(in.getUngroundedWarn()).isNull();
        assertThat(in.getSameAction()).isNull();
        assertThat(in.getNoProgressVariable()).isNull();
    }

    @Test
    void anAgentWithoutOneHasNone_andTheWordsStayUsableAsNames() {
        LoomScript s = parse("agent A { model: \"m\" }\nworkflow W(inspect, times, rounds) { note \"{inspect} {times} {rounds}\" }");
        assertThat(s.getAgents().get(0).getInspect()).isNull();
    }

    @Test
    void badInputIsRefusedWithWhatWasExpected() {
        assertThatThrownBy(() -> inspectOf("same_action: 0 times")).hasMessageContaining("whole number from 1");
        assertThatThrownBy(() -> inspectOf("same_action: 4")).hasMessageContaining("times");
        assertThatThrownBy(() -> inspectOf("same_action: 4 rounds")).hasMessageContaining("Expect 'times'");
        assertThatThrownBy(() -> inspectOf("no_progress: 3 rounds review.score")).hasMessageContaining("on <variable>");
        assertThatThrownBy(() -> inspectOf("ungrounded_figures: medium at 2")).hasMessageContaining("warn at <n>");
        assertThatThrownBy(() -> inspectOf("paused_longer_than: soon")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void anUnknownRuleNamesTheNearestRule_andListsThem() {
        assertThatThrownBy(() -> inspectOf("same_actions: 4 times")).hasMessageContaining("Unknown inspect rule 'same_actions'").hasMessageContaining("same_action").hasMessageContaining("tool_failures");
    }

    @Test
    void anAgentCanHaveOnlyOneBlock() {
        assertThatThrownBy(() -> parse("agent A { model: \"m\" inspect { same_action: 3 times } inspect { same_output: 3 times } }")).hasMessageContaining("only one inspect block");
    }

    // ---- weave check ----

    private static List<ScriptValidator.Problem> check(String source) {
        return new ScriptValidator().validate(parse(source), new ScriptValidator.Context());
    }

    private static final String FLOW = "workflow W(topic) { delegate \"x\" to Editor -> review\n delegate \"y {review}\" to Editor -> final\n note \"{final}\" }";

    @Test
    void aVariableThatExistsPasses_oneThatDoesNotIsAnErrorWithAGuess() {
        String ok = "agent Editor { model: \"m\" inspect { no_progress: 3 rounds on review.score } }\n" + FLOW;
        assertThat(check(ok)).noneMatch(p -> p.message().contains("no_progress"));
        String bad = "agent Editor { model: \"m\" inspect { no_progress: 3 rounds on reveiw.score } }\n" + FLOW;
        assertThat(check(bad)).anySatisfy(p -> {
            assertThat(p.severity()).isEqualTo(ScriptValidator.Severity.ERROR);
            assertThat(p.message()).contains("no workflow variable named reveiw").contains("review");
        });
    }

    @Test
    void aWorkflowParameterIsAVariableToo() {
        assertThat(check("agent Editor { model: \"m\" inspect { no_progress: 2 rounds on topic } }\n" + FLOW)).noneMatch(p -> p.message().contains("no_progress"));
    }

    @Test
    void warnAtOrAboveHighIsAnError_andAnEmptyBlockIsAWarning() {
        assertThat(check("agent Editor { model: \"m\" inspect { ungrounded_figures: warn at 4, high at 4 } }\n" + FLOW))
                .anySatisfy(p -> assertThat(p.message()).contains("must be lower than"));
        assertThat(check("agent Editor { model: \"m\" inspect { } }\n" + FLOW))
                .anySatisfy(p -> {
                    assertThat(p.severity()).isEqualTo(ScriptValidator.Severity.WARNING);
                    assertThat(p.message()).contains("does nothing");
                });
    }
}
