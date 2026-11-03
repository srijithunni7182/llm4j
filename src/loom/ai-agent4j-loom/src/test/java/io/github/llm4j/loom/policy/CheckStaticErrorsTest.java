package io.github.llm4j.loom.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.ScriptValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckStaticErrorsTest {

    @TempDir Path dir;

    static final String HEAD =
            "evaluator E { model: \"m\" }\npolicy P: \"house-rules\"\nagent A { model: \"m\" system: \"s\" }\n";

    List<String> errors(String statement) throws Exception {
        Files.createDirectories(dir.resolve("policies"));
        Files.writeString(dir.resolve("policies/house-rules.md"), "## r_one\nBreaking it.\n");
        var s = PolicyParsesTest.parse(HEAD + "workflow Main(q) {\n  set t = q\n  " + statement + "\n}\n");
        return new ScriptValidator()
                .validate(s, new ScriptValidator.Context().baseDir(dir)).stream()
                        .filter(p -> p.severity() == ScriptValidator.Severity.ERROR)
                        .map(ScriptValidator.Problem::message)
                        .toList();
    }

    @Test
    void aGoodCheckHasNoErrors() throws Exception {
        assertThat(errors("check t against P using E on_violation { rewrite with A }")).isEmpty();
    }

    @Test
    void unknownEvaluatorPolicyAndRewriterAreNamed() throws Exception {
        assertThat(errors("check t against Nope using Ghost on_violation { rewrite with Nobody }"))
                .anyMatch(m -> m.contains("Ghost") && m.contains("not an evaluator"))
                .anyMatch(m -> m.contains("Nope") && m.contains("not a policy"))
                .anyMatch(m -> m.contains("Nobody") && m.contains("not an agent"));
    }

    @Test
    void anActionThatReplacesTheTextNeedsAWholeVariable() throws Exception {
        assertThat(errors("check t.field against P using E on_violation { ask person }"))
                .anyMatch(m -> m.contains("whole variable"));
    }

    @Test
    void replaceTextIsFixedSoBracesAreRefused() throws Exception {
        assertThat(errors("check t against P using E on_violation { replace with \"hello {t}\" }"))
                .anyMatch(m -> m.contains("fixed text"));
    }
}
