/**
 * The words the language's lexer treats as keywords: a name cannot be one of them (`Lexer.java`, `KEYWORDS`). A test compares this
 * list with the lexer's own, so a new keyword cannot slip past rename.
 */
export const RESERVED_WORDS: ReadonlySet<string> = new Set([
    'agent', 'import', 'tool', 'workflow', 'model', 'system', 'role', 'tools', 'note', 'handoff', 'delegate', 'to', 'alt', 'else', 'loop',
    'until', 'human_prompt', 'retry', 'broadcast', 'mcp', 'mcp_servers', 'transport', 'cmd', 'persona', 'system_template', 'audit', 'path',
    'logger', 'knowledge', 'skills', 'memory', 'routing', 'strategy', 'threshold', 'guardrail', 'on_violation', 'embedding', 'chunk_size',
    'primary', 'fallback', 'type', 'parallel', 'schedule', 'pattern', 'observe', 'call', 'enum', 'list', 'output_schema', 'on_failure',
]);
