package io.github.llm4j.loom.extract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Phase 24, R6: {@code weave check} refuses each misuse with a message that says what to write. */
class ExtractorCheckErrorsTest {

    private static final String DECL =
            "extractor Invoice { model: \"m\" fields { vendor: string, total: number } optional: [total] }\n";

    static Stream<Arguments> misuses() {
        return Stream.of(
                Arguments.of(
                        DECL + "workflow W(t) { extract Invoce from t -> r }",
                        "no extractor named Invoce",
                        "did you mean Invoice"),
                Arguments.of(
                        "workflow W(t) { extract Invoice from t -> r }",
                        "no extractor is declared",
                        "no extractor named Invoice"),
                Arguments.of(
                        "extractor E { model: \"m\" instructions: \"Read {x}\" fields { a: string } }\n"
                                + "workflow W(t) { extract E from t -> r }",
                        "instructions are fixed",
                        "from (name = …)"),
                Arguments.of(
                        DECL + "workflow W(t) { extract Invoice from t -> r\n alt (r.totl == missing) { note \"x\" } }",
                        "r has no field totl",
                        "did you mean total"),
                Arguments.of(
                        DECL + "workflow W(t) { extract Invoice from t -> r\n alt (r.vendor == missing) { note \"x\" } }",
                        "r.vendor is required",
                        "can never be missing"),
                Arguments.of(
                        DECL + "workflow W(t) { extract Invoice from nothing -> r }",
                        "nothing is not defined",
                        "workflow W"),
                Arguments.of(
                        DECL + DECL + "workflow W(t) { extract Invoice from t -> r }",
                        "declared twice",
                        "Invoice"),
                Arguments.of(
                        "extractor E { model: \"m\" fields { a: string } pii: hide }\n"
                                + "workflow W(t) { extract E from t -> r }",
                        "pii: use mask, block or warn",
                        "hide"));
    }

    @ParameterizedTest
    @MethodSource("misuses")
    void eachMisuseIsAnErrorThatSaysWhatToWrite(String source, String first, String second) {
        List<String> errors = errorsOf(source);

        assertThat(String.join("\n", errors)).contains(first).contains(second);
    }

    @org.junit.jupiter.api.Test
    void aCorrectScriptHasNoErrors() {
        String source =
                DECL
                        + "workflow W(t) { extract Invoice from t -> r\n"
                        + " alt (r.total == missing) { note \"none\" } else { note \"{r.total}\" } }";

        assertThat(errorsOf(source)).isEmpty();
    }

    private static List<String> errorsOf(String source) {
        var script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        return new ScriptValidator()
                .validate(script, new ScriptValidator.Context().registeredTools(java.util.Set.of()))
                .stream()
                .filter(p -> p.severity() == ScriptValidator.Severity.ERROR)
                .map(ScriptValidator.Problem::toString)
                .toList();
    }
}
