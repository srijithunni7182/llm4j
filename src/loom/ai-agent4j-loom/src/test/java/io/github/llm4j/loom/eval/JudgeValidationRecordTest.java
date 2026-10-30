package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 19 R12: the report says a judge was validated only when a validation of that very model,
 * that agreed with the people, is on record beside the dataset.
 */
class JudgeValidationRecordTest {
    @TempDir Path eval;

    private static JudgeValidation.Result result(boolean usable) {
        return usable
                ? new JudgeValidation.Result(40, 0.9, false, 0.85, 0.1, java.util.List.of())
                : new JudgeValidation.Result(40, 0.9, false, 0.4, 0.1, java.util.List.of("c1"));
    }

    @Test
    void aModelWithAUsableValidationOnRecordIsValidated() throws Exception {
        JudgeValidationRecord.write(eval, "judge-model", result(true));

        assertThat(JudgeValidationRecord.validatedFor(eval, "judge-model")).isTrue();
        assertThat(Files.readString(eval.resolve("judge-validation.json"))).contains("judge-model").contains("0.85");
    }

    @Test
    void aDifferentModelIsNotCoveredByAnotherModelsValidation() throws Exception {
        JudgeValidationRecord.write(eval, "judge-model", result(true));

        assertThat(JudgeValidationRecord.validatedFor(eval, "another-model")).isFalse();
        assertThat(JudgeValidationRecord.validatedFor(eval, "judge-model, another-model")).isFalse();
    }

    @Test
    void aValidationThatFailedDoesNotMakeAJudgeValidated() throws Exception {
        JudgeValidationRecord.write(eval, "judge-model", result(false));

        assertThat(JudgeValidationRecord.validatedFor(eval, "judge-model")).isFalse();
    }

    @Test
    void noRecordAnUnreadableRecordOrNoModelMeansNotValidated() throws Exception {
        assertThat(JudgeValidationRecord.validatedFor(eval, "judge-model")).isFalse();
        Files.writeString(eval.resolve("judge-validation.json"), "{ not json");
        assertThat(JudgeValidationRecord.validatedFor(eval, "judge-model")).isFalse();
        assertThat(JudgeValidationRecord.validatedFor(eval, null)).isFalse();
    }

    @Test
    void aNewValidationReplacesTheOldOne() throws Exception {
        JudgeValidationRecord.write(eval, "judge-model", result(true));
        JudgeValidationRecord.write(eval, "judge-model", result(false));

        assertThat(JudgeValidationRecord.validatedFor(eval, "judge-model")).isFalse();
    }
}
