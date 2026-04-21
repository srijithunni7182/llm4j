package io.github.llm4j.loom.lexer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Lexer {
    private static final Map<String, TokenType> KEYWORDS = new HashMap<>();

    static {
        KEYWORDS.put("agent",           TokenType.AGENT);
        KEYWORDS.put("import",          TokenType.IMPORT);
        KEYWORDS.put("tool",            TokenType.TOOL);
        KEYWORDS.put("workflow",        TokenType.WORKFLOW);
        KEYWORDS.put("model",           TokenType.MODEL);
        KEYWORDS.put("system",          TokenType.SYSTEM);
        KEYWORDS.put("role",            TokenType.ROLE);
        KEYWORDS.put("tools",           TokenType.TOOLS);
        KEYWORDS.put("note",            TokenType.NOTE);
        KEYWORDS.put("handoff",         TokenType.HANDOFF);
        KEYWORDS.put("delegate",        TokenType.DELEGATE);
        KEYWORDS.put("to",              TokenType.TO);
        KEYWORDS.put("alt",             TokenType.ALT);
        KEYWORDS.put("else",            TokenType.ELSE);
        KEYWORDS.put("loop",            TokenType.LOOP);
        KEYWORDS.put("until",           TokenType.UNTIL);
        KEYWORDS.put("human_prompt",    TokenType.HUMAN_PROMPT);
        KEYWORDS.put("retry",           TokenType.RETRY);
        KEYWORDS.put("broadcast",       TokenType.BROADCAST);
        // Tier 1 extensions
        KEYWORDS.put("mcp",             TokenType.MCP);
        KEYWORDS.put("mcp_servers",     TokenType.MCP_SERVERS);
        KEYWORDS.put("transport",       TokenType.TRANSPORT);
        KEYWORDS.put("cmd",             TokenType.CMD);
        KEYWORDS.put("persona",         TokenType.PERSONA);
        KEYWORDS.put("system_template", TokenType.SYSTEM_TEMPLATE);
        KEYWORDS.put("audit",           TokenType.AUDIT);
        KEYWORDS.put("path",            TokenType.PATH);
        KEYWORDS.put("logger",          TokenType.LOGGER);
        // Tier 2 extensions
        KEYWORDS.put("knowledge",       TokenType.KNOWLEDGE);
        KEYWORDS.put("skills",          TokenType.SKILLS);
        KEYWORDS.put("memory",          TokenType.MEMORY);
        KEYWORDS.put("routing",         TokenType.ROUTING);
        KEYWORDS.put("strategy",        TokenType.STRATEGY);
        KEYWORDS.put("threshold",       TokenType.THRESHOLD);
        KEYWORDS.put("guardrail",       TokenType.GUARDRAIL);
        KEYWORDS.put("on_violation",    TokenType.ON_VIOLATION);
        KEYWORDS.put("embedding",       TokenType.EMBEDDING);
        KEYWORDS.put("chunk_size",      TokenType.CHUNK_SIZE);
        KEYWORDS.put("primary",         TokenType.PRIMARY);
        KEYWORDS.put("fallback",        TokenType.FALLBACK);
        KEYWORDS.put("type",            TokenType.TYPE);
        // Tier 3 extensions
        KEYWORDS.put("parallel",        TokenType.PARALLEL);
        KEYWORDS.put("schedule",        TokenType.SCHEDULE);
        KEYWORDS.put("pattern",         TokenType.PATTERN);
        KEYWORDS.put("observe",         TokenType.OBSERVE);
        // Frontier features
        KEYWORDS.put("call",            TokenType.CALL);
        KEYWORDS.put("enum",            TokenType.ENUM);
        KEYWORDS.put("list",            TokenType.LIST);
        KEYWORDS.put("output_schema",   TokenType.OUTPUT_SCHEMA);
        KEYWORDS.put("on_failure",      TokenType.ON_FAILURE);
    }

    private final String source;
    private int start = 0;
    private int current = 0;
    private int line = 1;
    private int columnStart = 1;
    private final List<Token> tokens = new ArrayList<>();

    public Lexer(String source) {
        this.source = source;
    }

    public List<Token> tokenize() {
        while (!isAtEnd()) {
            start = current;
            scanToken();
        }
        tokens.add(new Token(TokenType.EOF, "", line, current - columnStart + 1));
        return tokens;
    }

    private void scanToken() {
        char c = advance();
        switch (c) {
            case '{': addToken(TokenType.LBRACE); break;
            case '}': addToken(TokenType.RBRACE); break;
            case '(': addToken(TokenType.LPAREN); break;
            case ')': addToken(TokenType.RPAREN); break;
            case '[': addToken(TokenType.LBRACKET); break;
            case ']': addToken(TokenType.RBRACKET); break;
            case ',': addToken(TokenType.COMMA); break;
            case ':': addToken(TokenType.COLON); break;
            case '+': addToken(TokenType.PLUS); break;
            case '-':
                if (match('>')) {
                    addToken(TokenType.ARROW);
                } else {
                    // It could be a minus, but we haven't defined minus yet. Let's just ignore or throw.
                    throw new RuntimeException("Unexpected character '-' at line " + line);
                }
                break;
            case '=':
                if (match('=')) addToken(TokenType.EQUALS);
                else addToken(TokenType.ASSIGN);
                break;
            case '<':
                if (match('=')) addToken(TokenType.LTE);
                else addToken(TokenType.LT);
                break;
            case '>':
                if (match('=')) addToken(TokenType.GTE);
                else addToken(TokenType.GT);
                break;
            case '!':
                if (match('=')) addToken(TokenType.NOT_EQUALS);
                else throw new RuntimeException("Unexpected character '!' at line " + line);
                break;
            case '/':
                if (match('/')) {
                    // A comment goes until the end of the line.
                    while (peek() != '\n' && !isAtEnd()) advance();
                } else {
                    throw new RuntimeException("Unexpected character '/' at line " + line);
                }
                break;
            case ' ':
            case '\r':
            case '\t':
                // Ignore whitespace.
                break;
            case '\n':
                line++;
                columnStart = current;
                break;
            case '"': string(); break;
            default:
                if (isDigit(c)) {
                    number();
                } else if (isAlpha(c)) {
                    identifier();
                } else {
                    throw new RuntimeException("Unexpected character '" + c + "' at line " + line);
                }
                break;
        }
    }

    private void string() {
        while (peek() != '"' && !isAtEnd()) {
            if (peek() == '\n') {
                line++;
                columnStart = current;
            }
            advance();
        }

        if (isAtEnd()) {
            throw new RuntimeException("Unterminated string at line " + line);
        }

        // The closing ".
        advance();

        // Trim the surrounding quotes.
        String value = source.substring(start + 1, current - 1);
        addToken(TokenType.STRING_LITERAL, value);
    }

    private void number() {
        while (isDigit(peek())) advance();
        
        // Look for a fractional part.
        if (peek() == '.' && isDigit(peekNext())) {
            // Consume the "."
            advance();

            while (isDigit(peek())) advance();
        }

        addToken(TokenType.NUMBER_LITERAL, source.substring(start, current));
    }

    private void identifier() {
        while (isAlphaNumeric(peek())) advance();

        String text = source.substring(start, current);
        TokenType type = KEYWORDS.get(text);
        if (type == null) type = TokenType.IDENTIFIER;
        addToken(type);
    }

    private boolean match(char expected) {
        if (isAtEnd()) return false;
        if (source.charAt(current) != expected) return false;
        current++;
        return true;
    }

    private char peek() {
        if (isAtEnd()) return '\0';
        return source.charAt(current);
    }

    private char peekNext() {
        if (current + 1 >= source.length()) return '\0';
        return source.charAt(current + 1);
    }

    private boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') ||
               (c >= 'A' && c <= 'Z') ||
                c == '_';
    }

    private boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c);
    }

    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private boolean isAtEnd() {
        return current >= source.length();
    }

    private char advance() {
        return source.charAt(current++);
    }

    private void addToken(TokenType type) {
        addToken(type, source.substring(start, current));
    }

    private void addToken(TokenType type, String value) {
        int col = start - columnStart + 1;
        tokens.add(new Token(type, value, line, col));
    }
}
