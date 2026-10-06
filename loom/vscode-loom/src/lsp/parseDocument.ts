/**
 * Lightweight Loom parser (TypeScript-native, no external dependency).
 *
 * Pulled out of server.ts so it can be unit-tested directly: it has no
 * vscode-languageserver dependency and no module-level side effects, unlike
 * server.ts (which calls `connection.listen()` at load time).
 */

/**
 * A lex/parse error found in the document.
 * `kind` distinguishes LexError from ParseError for diagnostic source tagging.
 */
export interface LoomError {
    kind: 'LexError' | 'ParseError';
    message: string;
    line: number;   // 0-based
    col: number;    // 0-based
    endCol: number; // 0-based, exclusive
}

/**
 * A defined agent or workflow extracted from the document.
 */
export interface LoomDefinition {
    kind: 'agent' | 'workflow';
    name: string;
    line: number;   // 0-based
    col: number;    // 0-based column of the name token
    endCol: number; // 0-based, exclusive
}

/**
 * A reference to an agent inside a delegate / handoff / broadcast statement.
 */
export interface AgentReference {
    name: string;
    line: number;   // 0-based
    col: number;    // 0-based column of the name token
    endCol: number; // 0-based, exclusive
}

/**
 * Full parse result for a document.
 */
export interface ParseResult {
    errors: LoomError[];
    definitions: LoomDefinition[];
    agentRefs: AgentReference[];
}

/**
 * The language lexer lets a word contain an apostrophe when a letter follows ("doesn't"); see `Lexer.java`, where a
 * word continues over `'` or `-` followed by a letter. This is the same rule.
 */
function isWordApostrophe(line: string, col: number): boolean {
    return line[col] === "'" && /[A-Za-z0-9_]/.test(line[col - 1] ?? '') && /[A-Za-z_]/.test(line[col + 1] ?? '');
}

/**
 * Characters that are valid anywhere in Loom source.
 * Anything outside this set is a LexError.
 * Requirements 3.3, 21.1
 */
export const VALID_CHAR_RE = /[\w\s"{}()\[\],:+%\->=<!./]/;

/**
 * Lightweight structural scanner for Loom source.
 *
 * Pass 1 — LexErrors: scan character-by-character for unrecognized chars.
 * Pass 2 — ParseErrors: check for unmatched braces and missing `->` in
 *           delegate/broadcast statements.
 * Pass 3 — Definitions: extract `agent <Name>` and `workflow <Name>`.
 * Pass 4 — Agent references: extract names from delegate/handoff/broadcast.
 *
 * Requirements: 3.1, 3.2, 3.3, 3.4, 4.1, 4.2, 4.3, 21.1, 21.2, 21.3
 */
export function parseDocument(text: string): ParseResult {
    const errors: LoomError[] = [];
    const definitions: LoomDefinition[] = [];
    const agentRefs: AgentReference[] = [];

    const lines = text.split('\n');

    // ── Pass 1: LexErrors ────────────────────────────────────────────────────
    // Also builds "logical" lines alongside: the same lines with string and
    // comment content blanked out, so later passes never mistake braces,
    // arrows or keywords inside a prompt string for real syntax. A string
    // literal may embed real newlines — Loom's own lexer (Lexer.java) allows
    // multi-line delegate prompts — so `inString` has to carry across line
    // breaks instead of resetting at the top of each line. Resetting it is
    // what made every multi-line prompt read as an unterminated string
    // followed by garbage tokens.
    const logicalLines: string[] = new Array(lines.length);
    let inString = false;
    let stringStart: { line: number; col: number } | null = null;

    for (let lineIdx = 0; lineIdx < lines.length; lineIdx++) {
        const line = lines[lineIdx];
        const logical = line.split('');

        for (let col = 0; col < line.length; col++) {
            const ch = line[col];

            if (inString) {
                logical[col] = ' ';
                if (ch === '"') {
                    inString = false;
                    stringStart = null;
                }
                continue;
            }

            if (ch === '"') {
                logical[col] = ' ';
                inString = true;
                stringStart = { line: lineIdx, col };
                continue;
            }

            if (ch === '/' && col + 1 < line.length && line[col + 1] === '/') {
                for (let c = col; c < line.length; c++) logical[c] = ' ';
                break;
            }

            if (!VALID_CHAR_RE.test(ch) && !isWordApostrophe(line, col)) {
                errors.push({
                    kind: 'LexError',
                    message: `Unexpected character: '${ch}'`,
                    line: lineIdx,
                    col,
                    endCol: col + 1,
                });
            }
        }

        logicalLines[lineIdx] = logical.join('');
    }

    // Only a string still open at end-of-file is actually unterminated.
    if (inString && stringStart) {
        errors.push({
            kind: 'LexError',
            message: 'Unterminated string literal',
            line: stringStart.line,
            col: stringStart.col,
            endCol: lines[stringStart.line].length,
        });
    }

    // ── Pass 2: ParseErrors ──────────────────────────────────────────────────
    // 2a. Unmatched braces — scanned on the logical lines, so braces that
    // only appear inside a (possibly multi-line) string are never counted.
    const braceStack: Array<{ line: number; col: number }> = [];
    for (let lineIdx = 0; lineIdx < logicalLines.length; lineIdx++) {
        const line = logicalLines[lineIdx];

        for (let col = 0; col < line.length; col++) {
            const ch = line[col];

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

    // 2b. delegate / broadcast must contain `->` (handoff doesn't — see
    // LoomParser#parseHandoffStmt, it ends after the target agent with no
    // result variable, so it's intentionally excluded here).
    // The payload string before the arrow may span many lines; those
    // continuation lines are blank in logicalLines, so we skip over them and
    // check the first real line after the string closes.
    const STMT_KEYWORDS_RE = /^\s*(delegate|broadcast)\b/;
    for (let lineIdx = 0; lineIdx < logicalLines.length; lineIdx++) {
        const line = logicalLines[lineIdx];
        const match = STMT_KEYWORDS_RE.exec(line);
        if (!match) continue;

        let hasArrow = line.includes('->');
        if (!hasArrow) {
            for (let ahead = 1; lineIdx + ahead < logicalLines.length; ahead++) {
                const nextLine = logicalLines[lineIdx + ahead];
                if (nextLine.trim() === '') continue; // still inside the payload string
                hasArrow = nextLine.includes('->');
                break; // only the first real line after the string is checked
            }
        }
        if (!hasArrow) {
            const col = line.indexOf(match[1]);
            errors.push({
                kind: 'ParseError',
                message: `Missing '->' in '${match[1]}' statement`,
                line: lineIdx,
                col,
                endCol: col + match[1].length,
            });
        }
    }

    // ── Pass 3: Definitions ──────────────────────────────────────────────────
    // Extract `agent <Name>` and `workflow <Name>` definitions.
    const DEF_RE = /\b(agent|workflow)\s+(\w+)/g;
    for (let lineIdx = 0; lineIdx < logicalLines.length; lineIdx++) {
        const line = logicalLines[lineIdx];

        let m: RegExpExecArray | null;
        DEF_RE.lastIndex = 0;
        while ((m = DEF_RE.exec(line)) !== null) {
            const keyword = m[1] as 'agent' | 'workflow';
            const name = m[2];
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
    // Grammar (LoomParser#parseDelegateStmt / parseHandoffStmt /
    // parseBroadcastStmt):
    //   delegate  <payload> to <Agent>        -> <var>
    //   handoff   <payload> to <Agent>
    //   broadcast <payload> to [<Agent>, ...] -> <var>
    // The agent name sits between `to` and the `->` (or end of statement for
    // handoff) — NOT after the `->`, which names the result variable. The
    // previous version here captured the word after `->`, so it reported the
    // output variable as the "agent" on every single delegate/broadcast, and
    // never matched handoff at all (it required an arrow handoff doesn't have).
    //
    // A statement may open on one logical line and have its `to <Agent>` on
    // a later one (the payload string in between), so this scans the whole
    // joined logical text rather than one line at a time.
    const logicalText = logicalLines.join('\n');
    const lineStartOffsets: number[] = new Array(logicalLines.length);
    {
        let offset = 0;
        for (let i = 0; i < logicalLines.length; i++) {
            lineStartOffsets[i] = offset;
            offset += logicalLines[i].length + 1; // +1 for the '\n' joiner
        }
    }
    const offsetToPosition = (offset: number): { line: number; col: number } => {
        let line = 0;
        while (line + 1 < lineStartOffsets.length && lineStartOffsets[line + 1] <= offset) line++;
        return { line, col: offset - lineStartOffsets[line] };
    };
    const pushRef = (agentName: string, matchIndex: number, matchText: string): void => {
        const nameOffset = matchIndex + matchText.lastIndexOf(agentName);
        const pos = offsetToPosition(nameOffset);
        agentRefs.push({ name: agentName, line: pos.line, col: pos.col, endCol: pos.col + agentName.length });
    };

    const DELEGATE_RE = /\bdelegate\b[\s\S]*?\bto\s+(\w+)\s*->/g;
    const HANDOFF_RE = /\bhandoff\b[\s\S]*?\bto\s+(\w+)/g;
    const BROADCAST_RE = /\bbroadcast\b[\s\S]*?\bto\s*\[([^\]]*)\]/g;

    let m: RegExpExecArray | null;
    DELEGATE_RE.lastIndex = 0;
    while ((m = DELEGATE_RE.exec(logicalText)) !== null) {
        pushRef(m[1], m.index, m[0]);
    }

    HANDOFF_RE.lastIndex = 0;
    while ((m = HANDOFF_RE.exec(logicalText)) !== null) {
        pushRef(m[1], m.index, m[0]);
    }

    BROADCAST_RE.lastIndex = 0;
    while ((m = BROADCAST_RE.exec(logicalText)) !== null) {
        const listText = m[1];
        const listOffset = m.index + m[0].indexOf(listText);
        const NAME_RE = /\w+/g;
        let nm: RegExpExecArray | null;
        while ((nm = NAME_RE.exec(listText)) !== null) {
            const pos = offsetToPosition(listOffset + nm.index);
            agentRefs.push({ name: nm[0], line: pos.line, col: pos.col, endCol: pos.col + nm[0].length });
        }
    }

    return { errors, definitions, agentRefs };
}
