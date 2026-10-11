package io.github.llm4j.loom.autonomy.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("L1")
class LexerTest {

    private static List<String> kinds(String s) {
        return Lexer.lex(s).stream().map(t -> t.kind().name()).toList();
    }

    @Test
    void numbersPercentagesAndDurations() {
        var t = Lexer.lex("97% 0.8 14d 30s 2h 5m 500ms 42");
        assertThat(t.get(0).kind()).isEqualTo(Lexer.Kind.PERCENT);
        assertThat(t.get(0).number()).isEqualTo(0.97);
        assertThat(t.get(1).kind()).isEqualTo(Lexer.Kind.NUMBER);
        assertThat(t.get(1).number()).isEqualTo(0.8);
        assertThat(t.get(2).kind()).isEqualTo(Lexer.Kind.DURATION);
        assertThat(t.get(2).millis()).isEqualTo(14L * 24 * 3600 * 1000);
        assertThat(t.get(3).millis()).isEqualTo(30_000L);
        assertThat(t.get(4).millis()).isEqualTo(7_200_000L);
        assertThat(t.get(5).millis()).isEqualTo(300_000L);
        assertThat(t.get(6).millis()).isEqualTo(500L);
        assertThat(t.get(7).number()).isEqualTo(42.0);
    }

    @Test
    void wordsOperatorsAndPunctuation() {
        assertThat(kinds("a.b(c, 1) >= 2 and not d or e in [1]"))
                .containsExactly("IDENT", "DOT", "IDENT", "LPAREN", "IDENT", "COMMA", "NUMBER", "RPAREN", "GE", "NUMBER",
                        "AND", "NOT", "IDENT", "OR", "IDENT", "IN", "LBRACKET", "NUMBER", "RBRACKET", "EOF");
        assertThat(kinds("== != < <= > >= + - * /")).containsExactly("EQ", "NE", "LT", "LE", "GT", "GE", "PLUS", "MINUS", "STAR", "SLASH", "EOF");
    }

    @Test
    void stringsKeepTheirContentAndSupportEscapes() {
        var t = Lexer.lex("\"approve\" 'a\\'b' \"x\\\"y\"");
        assertThat(t.get(0).text()).isEqualTo("approve");
        assertThat(t.get(1).text()).isEqualTo("a'b");
        assertThat(t.get(2).text()).isEqualTo("x\"y");
    }

    @Test
    void booleansAreKeywordsAndIdentifiersMayContainUnderscoresAndDigits() {
        assertThat(kinds("true false views_7d")).containsExactly("TRUE", "FALSE", "IDENT", "EOF");
        assertThat(Lexer.lex("views_7d").get(0).text()).isEqualTo("views_7d");
    }

    @Test
    void aNumberFollowedByAnUnknownUnitIsRejectedWithItsPosition() {
        assertThatThrownBy(() -> Lexer.lex("5 + 3x")).isInstanceOf(ExprException.class).hasMessageContaining("3x").hasMessageContaining("column 5");
    }

    @Test
    void unterminatedStringsAndStrayCharactersAreRejected() {
        assertThatThrownBy(() -> Lexer.lex("\"abc")).isInstanceOf(ExprException.class).hasMessageContaining("unterminated");
        assertThatThrownBy(() -> Lexer.lex("a # b")).isInstanceOf(ExprException.class).hasMessageContaining("'#'");
        assertThatThrownBy(() -> Lexer.lex("a = b")).isInstanceOf(ExprException.class).hasMessageContaining("==");
    }

    @Test
    void tokensRememberTheirColumn() {
        var t = Lexer.lex("ab  >= 3");
        assertThat(t.get(0).column()).isEqualTo(1);
        assertThat(t.get(1).column()).isEqualTo(5);
        assertThat(t.get(2).column()).isEqualTo(8);
    }
}
