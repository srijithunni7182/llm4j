package io.github.llm4j.loom.lexer;

public enum TokenType {
    // Keywords — core
    AGENT,
    IMPORT,
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

    // Keywords — Tier 1 extensions
    /** Top-level {@code mcp ServerName { }} block. */
    MCP,
    /** {@code mcp_servers: [ServerA, ServerB]} inside an agent block. */
    MCP_SERVERS,
    /** {@code transport: "stdio"} inside an mcp block. */
    TRANSPORT,
    /** {@code cmd: "npx ..."} inside an mcp block. */
    CMD,
    /** {@code persona: "technicalAnalyst"} inside an agent block. */
    PERSONA,
    /** {@code system_template: "template-id"} inside an agent block. */
    SYSTEM_TEMPLATE,
    /** Top-level {@code audit { logger: "file", path: "..." }} block. */
    AUDIT,
    /** {@code path: "..."} inside audit or future knowledge blocks. */
    PATH,
    /** {@code logger: "file"|"noop"} inside an audit block. */
    LOGGER,

    // Keywords — Tier 2 extensions
    KNOWLEDGE,
    SKILLS,
    MEMORY,
    ROUTING,
    STRATEGY,
    THRESHOLD,
    GUARDRAIL,
    ON_VIOLATION,
    EMBEDDING,
    CHUNK_SIZE,
    PRIMARY,
    FALLBACK,
    TYPE,

    // Keywords — Tier 3 extensions
    PARALLEL,
    SCHEDULE,
    PATTERN,
    OBSERVE,

    // Frontier features
    CALL,
    ENUM,
    LIST,
    OUTPUT_SCHEMA,
    ON_FAILURE,

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
