package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R12 of docs/specs/28: the report says which judge was chosen and why, and cautions about self-judging. */
class JudgeInReportTest {

    private static EvalReport.Run run(Map<String, String> settings) {
        return new EvalReport.Run(
                "main.loom",
                false,
                List.of(new ScenarioResult("Helper", "agent", "helper.yaml", "h-1", "a", Status.PASS, List.of(), BigDecimal.ZERO, null)),
                0,
                null,
                Map.of(),
                new EvalReport.JudgeFacts("claude-haiku-5-5", 2, 10, 5, null, false),
                settings,
                "weave eval main.loom");
    }

    @Test
    void readThisFirstSaysWhichJudgeWasChosenAndWhy() {
        String text = EvalSections.terminal(run(Map.of("judge choice", "claude-haiku-5-5 judging gemini-2.5-pro (the cheapest other model this project can reach, from another provider)")));
        assertThat(text).contains("Chosen: claude-haiku-5-5 judging gemini-2.5-pro").contains("from another provider");
    }

    @Test
    void aSelfJudgingRunHasItsCautionUnderCautions() {
        String text = EvalSections.terminal(run(Map.of("judge choice", "m judging m (--judge same, on purpose)", "judge caution", "self-judging: scores may be biased toward the judged model's own style")));
        String cautions = text.substring(text.indexOf("== Cautions =="));
        assertThat(cautions).contains("Judge: self-judging").contains("biased");
    }

    @Test
    void aRunWithADifferentJudgeHasNoSuchCaution() {
        String text = EvalSections.terminal(run(Map.of("judge choice", "a judging b (--judge)")));
        assertThat(text).doesNotContain("self-judging");
    }
}
