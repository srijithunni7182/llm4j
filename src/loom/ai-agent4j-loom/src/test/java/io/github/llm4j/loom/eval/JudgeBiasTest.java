package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 19 R12, the known judge biases: a judge that scores longer outputs higher (verbosity) is
 * named, and a judge from the same model family as the system it judges (self-preference) may not
 * gate. Both are read from the reference set; neither needs a model.
 */
class JudgeBiasTest {

    private static List<JudgeValidation.Row> rows(boolean verboseJudge) {
        List<JudgeValidation.Row> out = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int human = 1 + (i % 5);
            int length = 20 + (i % 10) * 40;
            // the judge adds a point to the longer half whatever the people think
            int judge = verboseJudge ? Math.min(5, human + (length > 200 ? 1 : 0)) : human;
            out.add(new JudgeValidation.Row("c" + i, new int[] {human, human, human}, judge, "x".repeat(length)));
        }
        return out;
    }

    @Test
    void aJudgeThatFavoursLongOutputsIsNamedForVerbosity() {
        JudgeValidation.Result r = JudgeValidation.validate(rows(true), 40);

        assertThat(r.biasNotes()).anyMatch(n -> n.contains("verbosity") && n.contains("longer outputs"));
    }

    @Test
    void aJudgeThatIgnoresLengthIsNotNamed() {
        assertThat(JudgeValidation.validate(rows(false), 40).biasNotes()).noneMatch(n -> n.contains("verbosity"));
    }

    @Test
    void withoutTheOutputTextVerbosityCannotBeChecked() {
        List<JudgeValidation.Row> noText = new ArrayList<>();
        for (JudgeValidation.Row r : rows(true)) noText.add(new JudgeValidation.Row(r.id(), r.humans(), r.judge()));

        assertThat(JudgeValidation.validate(noText, 40).biasNotes()).anyMatch(n -> n.contains("Verbosity was not checked"));
    }

    @Test
    void theSameFamilyAsTheSystemIsNotUsableForGating() {
        JudgeValidation.Result r = JudgeValidation.validate(rows(false), 40, "claude-haiku-5-5", "claude-opus-5-5");

        assertThat(r.sameFamily()).isTrue();
        assertThat(r.usableForGating()).isFalse();
        assertThat(r.summary()).contains("same model family").contains("self-preference");
    }

    @Test
    void aDifferentFamilyIsFineAndTheFamilyIsTheNameBeforeTheFirstSeparator() {
        assertThat(JudgeValidation.family("gemini-2.5-flash")).isEqualTo("gemini");
        assertThat(JudgeValidation.family("anthropic/claude-opus-5-5")).isEqualTo("claude");
        assertThat(JudgeValidation.family("gpt-4o")).isEqualTo("gpt");
        assertThat(JudgeValidation.validate(rows(false), 40, "gemini-2.5-flash", "claude-opus-5-5").usableForGating()).isTrue();
    }
}
