package io.github.llm4j.loom.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.ScriptValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What {@code weave check} says about policies and evaluators. */
class PolicyCheckErrorsTest {

    @TempDir Path dir;

    List<ScriptValidator.Problem> check(String script, String policyFile) throws Exception {
        Files.createDirectories(dir.resolve("policies"));
        if (policyFile != null) Files.writeString(dir.resolve("policies/house-rules.md"), policyFile);
        var s = PolicyParsesTest.parse(script);
        return new ScriptValidator().validate(s, new ScriptValidator.Context().baseDir(dir));
    }

    static final String RULES = "## no_promise\nThe reply promises something.\n";
    static final String EVAL = "evaluator C { model: \"m\" }\n";

    static List<String> messages(List<ScriptValidator.Problem> ps) {
        return ps.stream().map(p -> p.construct() + ": " + p.message()).toList();
    }

    @Test
    void aGoodPolicyAndEvaluatorHaveNoProblems() throws Exception {
        assertThat(
                        check(EVAL + "policy P: \"house-rules\" checked by C\n", RULES).stream()
                                .filter(p -> p.severity() == ScriptValidator.Severity.ERROR))
                .isEmpty();
    }

    @Test
    void aMissingFileIsAnErrorThatSaysWhereToPutIt() throws Exception {
        assertThat(messages(check(EVAL + "policy P: \"house-rules\"\n", null)))
                .anyMatch(m -> m.startsWith("policy P") && m.contains("house-rules"));
    }

    @Test
    void aFileWithNoRuleIsAnError() throws Exception {
        assertThat(messages(check("policy P: \"house-rules\"\n", "# nothing\n")))
                .anyMatch(m -> m.contains("no rule"));
    }

    @Test
    void checkedByMustNameAnEvaluator() throws Exception {
        assertThat(messages(check("policy P: \"house-rules\" checked by Ghost\n", RULES)))
                .anyMatch(m -> m.contains("Ghost") && m.contains("not an evaluator"));
    }

    @Test
    void duplicateNamesAreErrors() throws Exception {
        var ms = messages(check(EVAL + EVAL + "policy P: \"house-rules\"\npolicy P: \"house-rules\"\n", RULES));
        assertThat(ms).anyMatch(m -> m.startsWith("evaluator C") && m.contains("twice"));
        assertThat(ms).anyMatch(m -> m.startsWith("policy P") && m.contains("twice"));
    }

    @Test
    void unsureBelowOnAOneSampleChatEvaluatorIsAWarning() throws Exception {
        var ps = check("evaluator C { model: \"m\" unsure below 0.9 }\n", null);
        assertThat(ps)
                .anyMatch(
                        p ->
                                p.severity() == ScriptValidator.Severity.WARNING
                                        && p.message().contains("samples: 3"));
    }

    @Test
    void unsureBelowWithThreeSamplesIsFine() throws Exception {
        assertThat(check("evaluator C { model: \"m\" unsure below 0.9 samples: 3 }\n", null)).isEmpty();
    }

    @Test
    void aBadPiiWordIsAnError() throws Exception {
        assertThat(messages(check("evaluator C { model: \"m\" pii: shred }\n", null)))
                .anyMatch(m -> m.contains("pii:"));
    }
}
