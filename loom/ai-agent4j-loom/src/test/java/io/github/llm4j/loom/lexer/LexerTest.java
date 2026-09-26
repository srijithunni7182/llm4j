package io.github.llm4j.loom.lexer;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class LexerTest {

    @Test
    public void testTokenizeAgentDef() {
        String input = """
            agent Writer {
                model: "gpt-4"
                system: "You are a writer"
                tools: [WebSearch, Calculator]
            }
        """;

        Lexer lexer = new Lexer(input);
        List<Token> tokens = lexer.tokenize();

        assertEquals(TokenType.AGENT, tokens.get(0).getType());
        assertEquals(TokenType.IDENTIFIER, tokens.get(1).getType());
        assertEquals("Writer", tokens.get(1).getValue());
        assertEquals(TokenType.LBRACE, tokens.get(2).getType());

        assertEquals(TokenType.MODEL, tokens.get(3).getType());
        assertEquals(TokenType.COLON, tokens.get(4).getType());
        assertEquals(TokenType.STRING_LITERAL, tokens.get(5).getType());
        assertEquals("gpt-4", tokens.get(5).getValue());

        assertEquals(TokenType.SYSTEM, tokens.get(6).getType());
        assertEquals(TokenType.COLON, tokens.get(7).getType());
        assertEquals(TokenType.STRING_LITERAL, tokens.get(8).getType());
        assertEquals("You are a writer", tokens.get(8).getValue());

        assertEquals(TokenType.TOOLS, tokens.get(9).getType());
        assertEquals(TokenType.COLON, tokens.get(10).getType());
        assertEquals(TokenType.LBRACKET, tokens.get(11).getType());
        assertEquals(TokenType.IDENTIFIER, tokens.get(12).getType());
        assertEquals("WebSearch", tokens.get(12).getValue());
        assertEquals(TokenType.COMMA, tokens.get(13).getType());
        assertEquals(TokenType.IDENTIFIER, tokens.get(14).getType());
        assertEquals("Calculator", tokens.get(14).getValue());
        assertEquals(TokenType.RBRACKET, tokens.get(15).getType());

        assertEquals(TokenType.RBRACE, tokens.get(16).getType());
        assertEquals(TokenType.EOF, tokens.get(17).getType());
    }

    @Test
    public void testDottedPathLexesAsSingleIdentifier() {
        List<Token> tokens = new Lexer("alt (report.status == \"SECURE\") { }").tokenize();

        assertEquals(TokenType.ALT, tokens.get(0).getType());
        assertEquals(TokenType.LPAREN, tokens.get(1).getType());
        assertEquals(TokenType.IDENTIFIER, tokens.get(2).getType());
        assertEquals("report.status", tokens.get(2).getValue());
        assertEquals("SECURE", tokens.get(4).getValue());
    }

    @Test
    public void testTrailingDotIsNotConsumedIntoIdentifier() {
        // A dot must be followed by a letter to continue the path, so numbers keep their meaning.
        List<Token> tokens = new Lexer("retry 2.5").tokenize();
        assertEquals(TokenType.NUMBER_LITERAL, tokens.get(1).getType());
        assertEquals("2.5", tokens.get(1).getValue());
    }
}
