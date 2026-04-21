/**
 * Loom Language Server — `src/lsp/server.ts`
 *
 * Implements a full LSP server for `.loom` files using
 * `vscode-languageserver/node` and `vscode-languageserver-textdocument`.
 *
 * Tasks covered:
 *   4.1 — Server setup and capabilities
 *   4.2 — Diagnostics with 300 ms debounce (LexErrors + ParseErrors)
 *   4.3 — Undefined agent reference detection (Warning diagnostics)
 *   4.4 — Hover (keywords + defined agents/workflows)
 *   4.5 — Go-to-definition (agent / workflow definitions)
 *   4.6 — Completion (keywords + document-defined agents/workflows)
 *
 * Requirements: 2.1, 2.2, 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 4.2, 4.3,
 *               5.1, 5.2, 5.3, 5.4, 21.1, 21.2, 21.3
 */

import {
    createConnection,
    ProposedFeatures,
    TextDocumentSyncKind,
    InitializeResult,
    Diagnostic,
    DiagnosticSeverity,
    Hover,
    Location,
    CompletionItem,
    CompletionItemKind,
    TextDocumentPositionParams,
    MarkupKind,
    Range,
    Position,
} from 'vscode-languageserver/node';

import {
    TextDocument,
} from 'vscode-languageserver-textdocument';

import {
    TextDocuments,
} from 'vscode-languageserver/node';

// ─────────────────────────────────────────────────────────────────────────────
// Task 4.1 — Server setup and capabilities
// ─────────────────────────────────────────────────────────────────────────────

/** IPC connection to the VS Code extension host. */
const connection = createConnection(ProposedFeatures.all);

/** Manages open text documents and keeps them in sync. */
const documents: TextDocuments<TextDocument> = new TextDocuments(TextDocument);

connection.onInitialize((): InitializeResult => {
    return {
        capabilities: {
            textDocumentSync: TextDocumentSyncKind.Incremental,
            hoverProvider: true,
            definitionProvider: true,
            completionProvider: {
                resolveProvider: false,
                triggerCharacters: [' ', '.'],
            },
        },
    };
});

// ─────────────────────────────────────────────────────────────────────────────
// Loom keyword catalogue
// ─────────────────────────────────────────────────────────────────────────────

/**
 * All Loom DSL keywords with their markdown hover documentation.
 * Requirements 5.1, 5.4
 */
const LOOM_KEYWORDS: Record<string, string> = {
    agent:
        '**agent** `<Name>`\n\nDefines a named AI agent with a model, optional system prompt, tools, skills, persona, and routing policy.',
    workflow:
        '**workflow** `<Name>([params])`\n\nDefines a named workflow that can be executed or called as a sub-workflow.',
    delegate:
        '**delegate** `"<payload>" -> <Agent> -> <output_var>`\n\nSends a prompt to the specified agent and stores the response in `output_var`.',
    handoff:
        '**handoff** `"<payload>" -> <Agent>`\n\nTransfers control to the specified agent, terminating the current execution branch.',
    broadcast:
        '**broadcast** `"<payload>" -> <output_var>`\n\nSends a prompt to all agents in the script and stores each response.',
    parallel:
        '**parallel** `{ ... }`\n\nExecutes all contained statements concurrently and waits for all branches to complete.',
    loop:
        '**loop** `until <condition> { ... }`\n\nRepeats the body until the condition evaluates to true.',
    alt:
        '**alt** `{ ... }`\n\nDefines alternative execution branches (conditional routing).',
    call:
        '**call** `<Workflow>([args]) -> <output_var>`\n\nInvokes a sub-workflow in an isolated variable scope and writes only the output variable back.',
    guardrail:
        '**guardrail** `{ ... }`\n\nWraps statements with safety checks; aborts execution if a guardrail condition is violated.',
    observe:
        '**observe** `<Agent>`\n\nPassively observes the output of an agent without modifying the variable context.',
    schedule:
        '**schedule** `<Name> { ... }`\n\nDefines a scheduled trigger for a workflow.',
    routing:
        '**routing** `<Name> { ... }`\n\nDefines a routing policy that selects an agent based on runtime conditions.',
    import:
        '**import** `"<path>"`\n\nImports agent and workflow definitions from another `.loom` file.',
    mcp:
        '**mcp** `<Name> { ... }`\n\nRegisters a Model Context Protocol server for tool discovery.',
    audit:
        '**audit** `{ ... }`\n\nConfigures audit logging for the script.',
    note:
        '**note** `"<message>"`\n\nEmits a human-readable annotation into the execution trace (no-op at runtime).',
    retry:
        '**retry** `<N>`\n\nSpecifies the number of retry attempts for a `delegate` statement.',
    on_failure:
        '**on_failure** `{ ... }`\n\nDefines a fallback block executed when all retry attempts for a `delegate` are exhausted.',
};

/** Ordered list of keyword names (used for completion). */
const LOOM_KEYWORD_NAMES = Object.keys(LOOM_KEYWORDS);

// ─────────────────────────────────────────────────────────────────────────────
// Lightweight Loom parser (TypeScript-native, no external dependency)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A lex/parse error found in the document.
 * `kind` distinguishes LexError from ParseError for diagnostic source tagging.
 */
interface LoomError {
    kind: 'LexError' | 'ParseError';
    message: string;
    line: number;   // 0-based
    col: number;    // 0-based
    endCol: number; // 0-based, exclusive
}

/**
 * A defined agent or workflow extracted from the document.
 */
interface LoomDefinition {
    kind: 'agent' | 'workflow';
    name: string;
    line: number;   // 0-based
    col: number;    // 0-based column of the name token
    endCol: number; // 0-based, exclusive
}

/**
 * A reference to an agent inside a delegate / handoff / broadcast statement.
 */
interface AgentReference {
    name: string;
    line: number;   // 0-based
    col: number;    // 0-based column of the name token
    endCol: number; // 0-based, exclusive
}

/**
 * Full parse result for a document.
 */
interface ParseResult {
    errors: LoomError[];
    definitions: LoomDefinition[];
    agentRefs: AgentReference[];
}

/**
 * Characters that are valid anywhere in Loom source.
 * Anything outside this set is a LexError.
 * Requirements 3.3, 21.1
 */
const VALID_CHAR_RE = /[\w\s"{}()\[\],:+\->=<!./]/;

/**
 * Lightweight structural scanner for Loom source.
 *
 * Pass 1 — LexErrors: scan character-by-character for unrecognized chars.
 * Pass 2 — ParseErrors: check for unmatched braces and missing `->` in
 *           delegate/handoff/broadcast statements.
 * Pass 3 — Definitions: extract `agent <Name>` and `workflow <Name>`.
 * Pass 4 — Agent references: extract names from delegate/handoff/broadcast.
 *
 * Requirements: 3.1, 3.2, 3.3, 3.4, 4.1, 4.2, 4.3, 21.1, 21.2, 21.3
 */
function parseDocument(text: string): ParseResult {
    const errors: LoomError[] = [];
    const definitions: LoomDefinition[] = [];
    const agentRefs: AgentReference[] = [];

    const lines = text.split('\n');

    // ── Pass 1: LexErrors ────────────────────────────────────────────────────
    for (let lineIdx = 0; lineIdx < lines.length; lineIdx++) {
        const line = lines[lineIdx];
        let inString = false;
        let inComment = false;

        for (let col = 0; col < line.length; col++) {
            const ch = line[col];

            if (inComment) {
                // rest of line is a comment — skip
                break;
            }

            if (inString) {
                if (ch === '"') {
                    inString = false;
                }
                // all chars inside a string are valid
                continue;
            }

            if (ch === '"') {
                inString = true;
                continue;
            }

            if (ch === '/' && col + 1 < line.length && line[col + 1] === '/') {
                inComment = true;
                break;
            }

            if (!VALID_CHAR_RE.test(ch)) {
                errors.push({
                    kind: 'LexError',
                    message: `Unexpected character: '${ch}'`,
                    line: lineIdx,
                    col,
                    endCol: col + 1,
                });
            }
        }

        // Unterminated string literal on this line
        if (inString) {
            errors.push({
                kind: 'LexError',
                message: 'Unterminated string literal',
                line: lineIdx,
                col: line.lastIndexOf('"'),
                endCol: line.length,
            });
        }
    }

    // ── Pass 2: ParseErrors ──────────────────────────────────────────────────
    // 2a. Unmatched braces
    const braceStack: Array<{ line: number; col: number }> = [];
    for (let lineIdx = 0; lineIdx < lines.length; lineIdx++) {
        const line = lines[lineIdx];
        let inString = false;
        let inComment = false;

        for (let col = 0; col < line.length; col++) {
            const ch = line[col];

            if (inComment) break;

            if (inString) {
                if (ch === '"') inString = false;
                continue;
            }
            if (ch === '"') { inString = true; continue; }
            if (ch === '/' && col + 1 < line.length && line[col + 1] === '/') {
                inComment = true;
                break;
            }

            if (ch === '{') {
                braceStack.push({ line: lineIdx, col });
            } else if (ch === '}') {
                if (braceStack.length === 0) {
                    errors.push({
                        kind: 'ParseError',
                        message: "Unexpected '}' — no matching '{'",
                        line: lineIdx,
                        col,
                        endCol: col + 1,
                    });
                } else {
                    braceStack.pop();
                }
            }
        }
    }
    for (const unmatched of braceStack) {
        errors.push({
            kind: 'ParseError',
            message: "Unmatched '{' — missing closing '}'",
            line: unmatched.line,
            col: unmatched.col,
            endCol: unmatched.col + 1,
        });
    }

    // 2b. delegate / handoff / broadcast must contain `->`
    //     We check each non-comment, non-string line that starts with one of
    //     these keywords.
    const STMT_KEYWORDS_RE = /^\s*(delegate|handoff|broadcast)\b/;
    for (let lineIdx = 0; lineIdx < lines.length; lineIdx++) {
        const rawLine = lines[lineIdx];
        // Strip comments for this check
        const commentIdx = rawLine.indexOf('//');
        const line = commentIdx >= 0 ? rawLine.slice(0, commentIdx) : rawLine;

        if (STMT_KEYWORDS_RE.test(line)) {
            // A delegate/handoff/broadcast statement may span multiple lines
            // (until the next `;` or `}`), but for a lightweight check we
            // look for `->` on the same line or the next non-empty line.
            const hasArrow = line.includes('->');
            if (!hasArrow) {
                // Look ahead up to 3 lines for the arrow
                let found = false;
                for (let ahead = 1; ahead <= 3 && lineIdx + ahead < lines.length; ahead++) {
                    const nextLine = lines[lineIdx + ahead];
                    const nextComment = nextLine.indexOf('//');
                    const stripped = nextComment >= 0 ? nextLine.slice(0, nextComment) : nextLine;
                    if (stripped.includes('->')) { found = true; break; }
                    // Stop looking if we hit a closing brace or another keyword
                    if (/^\s*[{}]/.test(stripped) || STMT_KEYWORDS_RE.test(stripped)) break;
                }
                if (!found) {
                    const match = STMT_KEYWORDS_RE.exec(line);
                    const col = match ? line.indexOf(match[1]) : 0;
                    errors.push({
                        kind: 'ParseError',
                        message: `Missing '->' in '${match![1]}' statement`,
                        line: lineIdx,
                        col,
                        endCol: col + (match![1].length),
                    });
                }
            }
        }
    }

    // ── Pass 3: Definitions ──────────────────────────────────────────────────
    // Extract `agent <Name>` and `workflow <Name>` definitions.
    const DEF_RE = /\b(agent|workflow)\s+(\w+)/g;
    for (let lineIdx = 0; lineIdx < lines.length; lineIdx++) {
        const rawLine = lines[lineIdx];
        const commentIdx = rawLine.indexOf('//');
        const line = commentIdx >= 0 ? rawLine.slice(0, commentIdx) : rawLine;

        let m: RegExpExecArray | null;
        DEF_RE.lastIndex = 0;
        while ((m = DEF_RE.exec(line)) !== null) {
            const keyword = m[1] as 'agent' | 'workflow';
            const name = m[2];
            const nameStart = m.index + m[1].length + 1; // skip keyword + space
            // Adjust for leading whitespace in the match
            const actualNameStart = line.indexOf(name, m.index + m[1].length);
            definitions.push({
                kind: keyword,
                name,
                line: lineIdx,
                col: actualNameStart,
                endCol: actualNameStart + name.length,
            });
        }
    }

    // ── Pass 4: Agent references ─────────────────────────────────────────────
    // Extract agent names from delegate / handoff / broadcast statements.
    // Pattern: `delegate "..." -> AgentName -> ...`
    //          `handoff  "..." -> AgentName`
    //          `broadcast "..." -> varName` (no agent name — skip)
    //
    // We look for `-> <Identifier>` after the payload string.
    // For `broadcast` the arrow points to an output variable, not an agent,
    // so we skip it for undefined-agent checking.
    const DELEGATE_RE = /\bdelegate\b[^>]*->\s*(\w+)/g;
    const HANDOFF_RE  = /\bhandoff\b[^>]*->\s*(\w+)/g;

    for (let lineIdx = 0; lineIdx < lines.length; lineIdx++) {
        const rawLine = lines[lineIdx];
        const commentIdx = rawLine.indexOf('//');
        const line = commentIdx >= 0 ? rawLine.slice(0, commentIdx) : rawLine;

        for (const re of [DELEGATE_RE, HANDOFF_RE]) {
            re.lastIndex = 0;
            let m: RegExpExecArray | null;
            while ((m = re.exec(line)) !== null) {
                const agentName = m[1];
                const nameStart = line.lastIndexOf(agentName, m.index + m[0].length);
                agentRefs.push({
                    name: agentName,
                    line: lineIdx,
                    col: nameStart,
                    endCol: nameStart + agentName.length,
                });
            }
        }

        // broadcast: `broadcast "..." -> <output_var>` — the name after `->` is
        // an output variable, not an agent. However, the spec says broadcast
        // references agents too. We check for a second `->` pattern:
        // `broadcast "..." -> AgentName -> outputVar` is not standard Loom syntax;
        // broadcast dispatches to ALL agents. So we do NOT add agent refs for broadcast.
    }

    return { errors, definitions, agentRefs };
}

// ─────────────────────────────────────────────────────────────────────────────
// Task 4.2 — Diagnostics with 300 ms debounce
// ─────────────────────────────────────────────────────────────────────────────

/** Per-document debounce timers. */
const debounceTimers = new Map<string, ReturnType<typeof setTimeout>>();

/**
 * Validate a document and send diagnostics to the client.
 * Requirements: 3.1, 3.2, 3.3, 3.4, 4.1, 4.2, 4.3, 21.1, 21.2, 21.3
 */
function validateDocument(doc: TextDocument): void {
    const text = doc.getText();
    const result = parseDocument(text);

    const diagnostics: Diagnostic[] = [];

    // ── Lex / Parse error diagnostics (severity: Error) ──────────────────────
    for (const err of result.errors) {
        const range: Range = {
            start: Position.create(err.line, err.col),
            end:   Position.create(err.line, err.endCol),
        };
        diagnostics.push({
            severity: DiagnosticSeverity.Error,
            range,
            message: err.message,
            source: err.kind === 'LexError' ? 'loom-lex' : 'loom-parse',
        });
    }

    // ── Undefined agent reference diagnostics (severity: Warning) ────────────
    // Only emit when there are no lex/parse errors (successful parse).
    // Requirements: 4.1, 4.2, 4.3, 21.3
    if (result.errors.length === 0) {
        const definedAgents = new Set(
            result.definitions
                .filter(d => d.kind === 'agent')
                .map(d => d.name)
        );

        for (const ref of result.agentRefs) {
            if (!definedAgents.has(ref.name)) {
                const range: Range = {
                    start: Position.create(ref.line, ref.col),
                    end:   Position.create(ref.line, ref.endCol),
                };
                diagnostics.push({
                    severity: DiagnosticSeverity.Warning,
                    range,
                    message: `Undefined agent: '${ref.name}'`,
                    source: 'loom-parse',
                });
            }
        }
    }

    // Send diagnostics (empty array clears previous ones — Requirement 3.4)
    connection.sendDiagnostics({ uri: doc.uri, diagnostics });
}

documents.onDidChangeContent(change => {
    const uri = change.document.uri;

    // Cancel any pending validation for this document
    const existing = debounceTimers.get(uri);
    if (existing !== undefined) {
        clearTimeout(existing);
    }

    // Schedule a new validation after 300 ms (Requirement 3.5)
    const timer = setTimeout(() => {
        debounceTimers.delete(uri);
        const doc = documents.get(uri);
        if (doc) {
            validateDocument(doc);
        }
    }, 300);

    debounceTimers.set(uri, timer);
});

// ─────────────────────────────────────────────────────────────────────────────
// Utility: word at position
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns the word (identifier) at the given position in the document,
 * along with its start column.
 */
function wordAtPosition(
    doc: TextDocument,
    pos: { line: number; character: number }
): { word: string; startCol: number } | null {
    const lineText = doc.getText({
        start: { line: pos.line, character: 0 },
        end:   { line: pos.line, character: Number.MAX_SAFE_INTEGER },
    });

    const char = pos.character;
    // Walk left to find word start
    let start = char;
    while (start > 0 && /\w/.test(lineText[start - 1])) {
        start--;
    }
    // Walk right to find word end
    let end = char;
    while (end < lineText.length && /\w/.test(lineText[end])) {
        end++;
    }

    if (start === end) return null;
    return { word: lineText.slice(start, end), startCol: start };
}

// ─────────────────────────────────────────────────────────────────────────────
// Task 4.4 — Hover
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns hover documentation for the identifier under the cursor.
 *
 * Priority:
 *   1. Loom keyword → return keyword description.
 *   2. Defined agent or workflow in the document → return definition summary.
 *
 * Requirements: 5.1
 */
connection.onHover((params: TextDocumentPositionParams): Hover | null => {
    const doc = documents.get(params.textDocument.uri);
    if (!doc) return null;

    const hit = wordAtPosition(doc, params.position);
    if (!hit) return null;

    const { word } = hit;

    // 1. Keyword hover
    if (word in LOOM_KEYWORDS) {
        return {
            contents: {
                kind: MarkupKind.Markdown,
                value: LOOM_KEYWORDS[word],
            },
        };
    }

    // 2. Defined agent / workflow hover
    const result = parseDocument(doc.getText());
    const def = result.definitions.find(d => d.name === word);
    if (def) {
        const summary = def.kind === 'agent'
            ? `**agent** \`${def.name}\`\n\nDefined at line ${def.line + 1}.`
            : `**workflow** \`${def.name}\`\n\nDefined at line ${def.line + 1}.`;
        return {
            contents: {
                kind: MarkupKind.Markdown,
                value: summary,
            },
        };
    }

    return null;
});

// ─────────────────────────────────────────────────────────────────────────────
// Task 4.5 — Go-to-definition
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Resolves the identifier under the cursor to its definition location.
 *
 * Searches for `agent <word>` or `workflow <word>` in the document.
 * Returns `null` if no definition is found.
 *
 * Requirements: 5.2, 5.3
 */
connection.onDefinition((params: TextDocumentPositionParams): Location | null => {
    const doc = documents.get(params.textDocument.uri);
    if (!doc) return null;

    const hit = wordAtPosition(doc, params.position);
    if (!hit) return null;

    const { word } = hit;

    const result = parseDocument(doc.getText());
    const def = result.definitions.find(d => d.name === word);
    if (!def) return null;

    return Location.create(
        params.textDocument.uri,
        Range.create(
            Position.create(def.line, def.col),
            Position.create(def.line, def.endCol),
        )
    );
});

// ─────────────────────────────────────────────────────────────────────────────
// Task 4.6 — Completion
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns completion items for:
 *   - All Loom keywords
 *   - All agent names defined in the current document
 *   - All workflow names defined in the current document
 *
 * Requirements: 5.4
 */
connection.onCompletion((params: TextDocumentPositionParams): CompletionItem[] => {
    const doc = documents.get(params.textDocument.uri);
    const items: CompletionItem[] = [];

    // 1. Keyword completions
    for (const kw of LOOM_KEYWORD_NAMES) {
        items.push({
            label: kw,
            kind: CompletionItemKind.Keyword,
            detail: 'Loom keyword',
            documentation: {
                kind: MarkupKind.Markdown,
                value: LOOM_KEYWORDS[kw],
            },
        });
    }

    if (!doc) return items;

    // 2. Agent and workflow name completions from the current document
    const text = doc.getText();

    // Extract agent names: `agent <Name>`
    const agentRe = /\bagent\s+(\w+)/g;
    let m: RegExpExecArray | null;
    const seenAgents = new Set<string>();
    while ((m = agentRe.exec(text)) !== null) {
        const name = m[1];
        if (!seenAgents.has(name)) {
            seenAgents.add(name);
            items.push({
                label: name,
                kind: CompletionItemKind.Class,
                detail: 'agent',
            });
        }
    }

    // Extract workflow names: `workflow <Name>`
    const workflowRe = /\bworkflow\s+(\w+)/g;
    const seenWorkflows = new Set<string>();
    while ((m = workflowRe.exec(text)) !== null) {
        const name = m[1];
        if (!seenWorkflows.has(name)) {
            seenWorkflows.add(name);
            items.push({
                label: name,
                kind: CompletionItemKind.Function,
                detail: 'workflow',
            });
        }
    }

    return items;
});

// ─────────────────────────────────────────────────────────────────────────────
// Wire up and start
// ─────────────────────────────────────────────────────────────────────────────

// Make the text document manager listen on the connection for open, change,
// and close text document events.
documents.listen(connection);

// Listen on the connection.
connection.listen();
