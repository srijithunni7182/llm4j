package io.github.llm4j.loom.lexer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase 22 R4: a string can hold a double quote ({@code \"}) and a backslash ({@code \\}); any
 * other backslash stays as written, so {@code \n} and {@code \t} mean what they always meant (they
 * are read later).
 */
class StringEscapesTest {

    private static String valueOf(String literal) {
        List<Token> tokens = new Lexer("note " + literal + "\n").tokenize();
        return tokens.stream()
                .filter(t -> t.getType() == TokenType.STRING_LITERAL)
                .findFirst()
                .orElseThrow()
                .getValue();
    }

    @Test
    void anEscapedQuoteIsPartOfTheString() {
        assertThat(valueOf("\"say \\\"hi\\\" to {t}\"")).isEqualTo("say \"hi\" to {t}");
    }

    @Test
    void anEscapedBackslashIsOneBackslashSoAStringCanEndWithOne() {
        assertThat(valueOf("\"C:\\\\dir\\\\\"")).isEqualTo("C:\\dir\\");
    }

    @Test
    void anyOtherBackslashIsKeptAsWritten() {
        assertThat(valueOf("\"a\\nb\\tc\\qd\"")).isEqualTo("a\\nb\\tc\\qd");
    }

    @Test
    void aStringWithoutBackslashesIsUnchanged() {
        assertThat(valueOf("\"plain text\"")).isEqualTo("plain text");
    }

    @Test
    void aStringThatEndsInALoneBackslashBeforeTheQuoteSaysWhatToWrite() {
        assertThatThrownBy(() -> new Lexer("note \"ends with a backslash\\\"\n").tokenize())
                .hasMessageContaining("Unterminated string")
                .hasMessageContaining("\\\\");
    }

    @Test
    void theLineCountStaysRightAcrossAnEscapedQuoteOnAnEarlierLine() {
        List<Token> tokens = new Lexer("note \"a \\\" b\"\nnote \"c\"\n").tokenize();
        assertThat(tokens.stream().filter(t -> t.getType() == TokenType.STRING_LITERAL).toList())
                .extracting(Token::getValue)
                .containsExactly("a \" b", "c");
    }

    /**
     * No script in the repository changes meaning: the strings of every .loom file read the same
     * under the old rule (to the next quote) and the new one, unless the file has a backslash
     * before a quote, and those are listed here by name so a change is a decision.
     */
    @Test
    void everyScriptInTheRepositoryReadsTheSameExceptTheOnesListedHere() throws IOException {
        Path repo = Path.of("../../..").toAbsolutePath().normalize();
        List<String> changed = new ArrayList<>();
        try (Stream<Path> all = Files.walk(repo)) {
            for (Path p :
                    all.filter(f -> f.toString().endsWith(".loom") && Files.isRegularFile(f))
                            .filter(
                                    f ->
                                            !f.toString().contains("/target/")
                                                    && !f.toString().contains("/node_modules/"))
                            .toList()) {
                String text = Files.readString(p);
                if (!text.contains("\\\"")) continue;
                changed.add(repo.relativize(p).toString());
            }
        }
        // the one script written to show the escapes (CTK scenario string_escapes); any other would
        // be a script whose meaning changed, and has to be a decision
        assertThat(changed).containsExactly("src/loom/ctk/scripts/string_escapes.loom");
    }
}
