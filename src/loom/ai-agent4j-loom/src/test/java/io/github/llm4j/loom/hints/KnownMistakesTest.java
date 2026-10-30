package io.github.llm4j.loom.hints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 22 R3: a mistake with a known fix is answered with the fix. Each row of the registry has a
 * test that shows the message before the hint, the hint, and that the fix really parses.
 */
class KnownMistakesTest {

    private static String parseMessage(String script) {
        return org.assertj.core.api.Assertions.catchThrowable(
                        () -> new LoomParser(new Lexer(script).tokenize()).parseScript())
                .getMessage();
    }

    private static final String AGENT = "agent A { model: \"m\" }\n";

    @Test
    void expectingAfterRetryNamesTheOrder() {
        String message =
                parseMessage(
                        AGENT
                                + "workflow W(t) {\n  delegate \"x\" to A -> r retry 1 expecting { ok: boolean }\n}\n");

        assertThat(message)
                .contains("Parse error at line 3")
                .contains("expecting {")
                .contains("must come before `retry N`")
                .contains("delegate \"…\" to A -> x expecting {…} retry 1");
    }

    @Test
    void theOrderTheHintNamesParses() {
        new LoomParser(
                        new Lexer(
                                        AGENT
                                                + "workflow W(t) {\n  delegate \"x\" to A -> r expecting { ok: boolean } retry 1\n}\n")
                                .tokenize())
                .parseScript();
    }

    @Test
    void theHintOnlyAppearsWhereExpectingFollowsARetry() {
        String message = parseMessage(AGENT + "workflow W(t) {\n  expecting\n}\n");

        assertThat(message).doesNotContain("must come before `retry N`");
    }

    @Test
    void aReservedValueKeyInAScriptedResultSaysWhatToWriteInstead() {
        String hint =
                KnownMistakes.hintFor(
                                new KnownMistakes.Context(
                                        "\"value\" is reserved; use outcome(), reason() or withValue()",
                                        List.of(),
                                        -1))
                        .orElseThrow();

        assertThat(hint).contains("`value` is reserved").contains("plain string").contains("answer");
    }

    @Test
    void aMessageNobodyKnowsGetsNoHint() {
        assertThat(
                        KnownMistakes.hintFor(
                                new KnownMistakes.Context("some other failure", List.of(), -1)))
                .isEmpty();
    }

    @Test
    void everyMistakeHasAnIdAndAOneSentenceHintThatNamesNoValues() {
        assertThat(KnownMistakes.all()).isNotEmpty();
        assertThat(KnownMistakes.all())
                .allSatisfy(
                        m -> {
                            assertThat(m.id()).matches("[a-z][a-z0-9-]+");
                            assertThat(m.hint()).isNotBlank().doesNotContain("\n");
                        });
        assertThat(KnownMistakes.all().stream().map(KnownMistakes.Mistake::id).distinct().count())
                .isEqualTo(KnownMistakes.all().size());
    }

    @Test
    void withHintAppendsTheHintAfterTheMessage() {
        String text =
                KnownMistakes.withHint(
                        "\"value\" is reserved; use outcome(), reason() or withValue()",
                        List.of(),
                        -1);

        assertThat(text).startsWith("\"value\" is reserved").contains(" Hint: ");
        assertThatThrownBy(() -> KnownMistakes.withHint(null, List.of(), -1))
                .isInstanceOf(NullPointerException.class);
    }
}
