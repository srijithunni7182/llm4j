package io.github.llm4j.loom.lexer;

public enum TokenType {
    // Keywords
    AGENT,
    TOOL,
    WORKFLOW,
    MODEL,
    SYSTEM,
    ROLE,
    TOOLS,
    NOTE,
    HANDOFF,
    DELEGATE,
    TO,
    ALT,
    ELSE,
    LOOP,
    UNTIL,
    HUMAN_PROMPT,
    RETRY,
    BROADCAST,

    // Identifiers and Literals
    IDENTIFIER,
    STRING_LITERAL,
    NUMBER_LITERAL,

    // Symbols and Punctuation
    LBRACE,     // {
    RBRACE,     // }
    LPAREN,     // (
    RPAREN,     // )
    LBRACKET,   // [
    RBRACKET,   // ]
    COMMA,      // ,
    COLON,      // :
    ASSIGN,     // =
    EQUALS,     // ==
    NOT_EQUALS, // !=
    LT,         // <
    GT,         // >
    LTE,        // <=
    GTE,        // >=
    ARROW,      // ->
    PLUS,       // + (for string concat)

    // End of File
    EOF
}
