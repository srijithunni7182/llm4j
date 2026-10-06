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

import { parseDocument } from './parseDocument';

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
        '**loop** `until <condition> [max N] { ... } [on_exhausted { ... }]`\n\nRepeats the body until the condition evaluates to true. With `max N`, stops after N rounds and runs `on_exhausted`. `{_loopRound}` is the current round.',
    for:
        '**for each** `<item> in <list> { ... }`\n\nRuns the block once per list item; `{item.field}` and `{_index}` are visible inside. Prefix with `parallel` to run all items at once.',
    each:
        '**for each** `<item> in <list> { ... }`\n\nRuns the block once per list item; `{item.field}` and `{_index}` are visible inside.',
    max:
        '**max** `<N>`\n\nUpper bound on the rounds of a `loop until`.',
    on_exhausted:
        '**on_exhausted** `{ ... }`\n\nRuns when a bounded loop reaches `max` rounds without its condition becoming true. `{_loopRounds}` holds the rounds run.',
    expecting:
        '**expecting** `{ field: type, ... }`\n\nOverrides the agent\'s `output_schema` for this one `delegate`.',
    backoff:
        '**backoff** `<duration>`\n\nWait between retries (e.g. `2s`), doubling each attempt.',
    timeout:
        '**timeout** `<duration>`\n\nGives up on a delegate attempt that runs longer than this (e.g. `90s`); counts as a failed attempt.',
    budget:
        '**budget** `{ tokens: N  calls: N  cost: "$X"  warn_at: 80% }` or `budget N tokens`\n\nCaps what LLM calls may spend: at top level for the whole run, inside an agent (with `per_call`), or after a `delegate`, `broadcast`, `loop until` or `for each`. A call that can\'t be paid for is refused before it reaches the model and is never retried; `{_budget.remaining}` and friends are live.',
    per_call:
        '**per_call** `: N`\n\nInside an agent\'s `budget { }`: caps the output tokens of every answer that agent gives.',
    warn_at:
        '**warn_at** `: 80%`\n\nInside a `budget { }`: when to log a `budget_warning` (default 80%).',
    use:
        '**use** `: webhook | email | http | file | shell | sql | serpapi | duckduckgo | google_search | openapi | calculator | datetime | current_time | class`\n\nIn a `tool Name { }` block: the kind of tool. `webhook` posts to Slack, Discord or Teams; `email` sends over SMTP; `http` calls a REST API; `file` reads and writes text files in one directory; `shell` runs named programs; `sql` runs read-only queries. Secret options (`url`, `password`, `api_key`, `auth_value`, and credential headers) must be `env.NAME`.',
    checkpoint:
        '**checkpoint** `Name [starting with name = "value", ...]`\n\nNames a point in a workflow that a later `rewind` can go back to. `start` is the point before the first statement.',
    rewind:
        '**rewind** `to Name [when (condition)] at most N times [carrying name = "value"] [side effects: ask first | keep | repeat] [if it still fails { ... }] [if blocked { ... }]`\n\nGoes back to a checkpoint and runs what came after it again, as a new attempt that keeps the old one as history. A model call runs again; an identical side effect or an identical answer from a person is not repeated.',
    decision:
        '**decision** `Name { proposed by: Agent  choices: a, b, escalate  group cases by: v  remember: x, y  dangerous mistake: propose a, person decides b  ask: who  keep records for: 180 days  when the agent changes: start over | test it on past cases | keep the trust  trust { ... } }`\n\nEarned autonomy: an agent proposes, a person decides, and a ledger of both decides how much freedom the agent has (`watch`, `suggest`, `act`). See `weave autonomy` and `weave replay`.',
    decide:
        '**decide** `Name -> verdict`\n\nOne case of a decision. Binds `verdict` (what takes effect), `verdict_proposal` (the agent\'s choice) and `verdict_level` (watch, suggest or act). At `watch` the person decides without seeing the proposal, so the agent\'s agreement is measured honestly.',
    trust:
        '**trust** `{ start at watch  never go above suggest  to suggest: after 100 cases over 14 days, agreeing at least 90%  to act: after 300 cases over 30 days, agreeing at least 97%, with no dangerous mistakes  judge on the latest 300 cases  check 5% of cases with a person who doesn\'t see the proposal  always ask a person when amount > 200  drop to suggest when 2 dangerous mistakes in 50 cases  moving up needs approval from: someone }`\n\nInside a `decision`: the rules for earning and losing freedom. Agreement is judged by the lower end of its 95% Wilson interval, over blind cases only. Reaching `act` is a sentence the script has to write (`never go above act`).',
    on_unknown:
        '**on_unknown** `: skip | retry`\n\nIn a `webhook`, `email`, `http`, `file` or `shell` tool: what to do after a crash when it is not known whether an earlier call happened. `skip` (default) does not repeat it.',
    idempotency:
        '**idempotency** `: true`\n\nIn a `webhook` or `http` tool: send an `Idempotency-Key` header, the same one on every attempt of a call, so a receiver that supports it removes duplicates.',
    allow_to:
        '**allow_to** `: "*@example.com, boss@partner.org"`\n\nIn an `email` tool: the addresses the agent may choose. Use `to:` instead for a fixed recipient list.',
    allow_paths:
        '**allow_paths** `: "/repos/*, /search/**"`\n\nIn an `http` tool: the paths below `base_url` the agent may call (`*` within a segment, `**` across).',
    allow_private:
        '**allow_private** `: true`\n\nIn a `webhook` or `http` tool: allow addresses on a private network. Off by default, which also blocks cloud metadata addresses.',
    allow_interpreters:
        '**allow_interpreters** `: true`\n\nIn a `shell` tool: allow shells and interpreters in `allow:`. Off by default, because allowing one allows any program.',
    unattended:
        '**unattended** `: true`\n\nIn a `shell` tool: acknowledges that no person approves its calls. Otherwise an agent must list the tool under `approve:`.',
    env_pass:
        '**env_pass** `: "LANG, TZ"`\n\nIn a `shell` tool: environment variables to pass to the program. Otherwise it gets only `PATH`, `LANG` and `TZ`.',
    outbox:
        '**outbox** `: "outbox"`\n\nIn an `email` tool: write each message to this directory as an `.eml` file instead of sending it. For development.',
    approve:
        '**approve** `: [Tool, …] | all`\n\nOn an agent: those tool calls wait for a person\'s yes. Journaled per call; a durable run pauses until someone answers.',
    max_iterations:
        '**max_iterations** `: N`\n\nOn an agent: the most reasoning steps it may take for one task.',
    source:
        '**source** `: "docs/"`\n\nIn a `knowledge` block: a file or directory of md, txt, html, json or csv files.',
    provider:
        '**provider** `Name { use: gemini | ollama | sarvam  base_url: "…"  api_key: env.X }`\n\nA model endpoint with its own key. Agents use it as `model: "Name/<model>"`.',
    persona:
        '**persona** `Name { role: "…" expertise: "…" tone: "…" constraints: ["…"] }`\n\nA persona for agents (`persona: Name`); an agent\'s `system:` prompt follows it.',
    memory:
        '**memory** `{ conversation: "chats"  session: "{user}"  facts: "facts.json"  embedding: "…" }`\n\nOn an agent: its conversation per session and long-term facts, kept across runs.',
    voice:
        '**voice** `{ listen: "sarvam/…"  speak: "sarvam/…"  language: "hi-IN" }`\n\nOn an agent: audio-file tasks are transcribed; answers are spoken to `{result_audio}`.',
    guard:
        '**guard** `{ pii: mask | block | warn  bias: warn | block  bias_model: "…" }`\n\nOn an agent: keeps personal data from the model and out of results; checks answers for bias.',
    session:
        '**session** `: "{user_id}"`\n\nIn `memory`: whose conversation and facts; resolved per delegate (default: the agent\'s name).',
    facts:
        '**facts** `: "memory/facts.json" | memory`\n\nIn `memory`: long-term facts the agent saves with `save_memory_fact`, recalled by meaning (needs `embedding`).',
    top_k:
        '**top_k** `: 4`\n\nIn a `knowledge` block: how many passages each question retrieves.',
    per:
        '**per** `minute | hour | day`\n\nAfter a budget limit (`tokens: 100000 per day`): the budget refills each window.',
    when_exhausted:
        '**when_exhausted** `: stop | suspend | ask`\n\nInside a `budget { }`: stop the run (default), pause it until the budget refills (needs `per …`), or ask a person to allow more.',
    rate_limits:
        '**rate_limits** `{ on_limit: suspend  max_wait: 24h  max_resumes: 50 }`\n\nWhat a run does when a provider rate limit or quota stops it: pause and resume when it lifts (`suspend`, the default with a durable journal), `wait` inline, or `fail`.',
    on_limit:
        '**on_limit** `: suspend | wait | fail`\n\nInside `rate_limits { }`.',
    max_wait:
        '**max_wait** `: 24h`\n\nInside `rate_limits { }`: limits further away than this fail the run.',
    max_resumes:
        '**max_resumes** `: 50`\n\nInside `rate_limits { }`: a run resumed more often than this is failed.',
    cron:
        '**cron** `: "0 7 * * *"`\n\nIn a `schedule`: minute hour day-of-month month day-of-week, read in `timezone` (default UTC).',
    every:
        '**every** `: 6h`\n\nIn a `schedule`: a fixed interval instead of `cron`.',
    timezone:
        '**timezone** `: "Asia/Kolkata"`\n\nIn a `schedule`: the zone `cron` is read in.',
    misfire:
        '**misfire** `: run_once | skip`\n\nIn a `schedule`: after slots were missed while nothing ran, fire once or skip to the next slot.',
    overlap:
        '**overlap** `: skip | queue`\n\nIn a `schedule`: while an earlier run is still paused, skip this slot or start another run.',
    temperature:
        '**temperature** `<0.0–2.0>`\n\nSampling temperature for an agent: high for creative roles, low for checkers.',
    alt:
        '**alt** `{ ... }`\n\nDefines alternative execution branches (conditional routing).',
    human_prompt:
        '**human_prompt** `"<question>" -> <var>`\n\nAsks a person. With a run journal the run can suspend here (no thread held) and resume on any server once the answer is recorded.',
    call:
        '**call** `<Workflow>([args]) -> <output_var>`\n\nInvokes a sub-workflow in an isolated variable scope and writes only the output variable back.',
    guardrail:
        '**guardrail** `{ ... }`\n\nWraps statements with safety checks; aborts execution if a guardrail condition is violated.',
    observe:
        '**observe** `<Agent>`\n\nPassively observes the output of an agent without modifying the variable context.',
    schedule:
        '**schedule** `<Name> { cron: "0 7 * * *"  run: Workflow(arg="x") }`\n\nRuns a workflow (or `agent` + `task`) on a cron or `every` interval. With a trigger store it is persisted and survives restarts; `weave triggers install` lets the OS wake it.',
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
        '**retry** `<N> [backoff <duration>] [timeout <duration>]`\n\nSpecifies the number of retry attempts for a `delegate` statement.',
    on_failure:
        '**on_failure** `{ ... }`\n\nDefines a fallback block executed when all retry attempts for a `delegate` are exhausted.',
};

/** Ordered list of keyword names (used for completion). */
const LOOM_KEYWORD_NAMES = Object.keys(LOOM_KEYWORDS);

// ─────────────────────────────────────────────────────────────────────────────
// Lightweight Loom parser (TypeScript-native, no external dependency)
// ─────────────────────────────────────────────────────────────────────────────
// Moved to ./parseDocument.ts so its pure diagnostic logic can be unit-tested
// without pulling in the vscode-languageserver connection this file creates.

// ─────────────────────────────────────────────────────────────────────────────
// Task 4.2 — Diagnostics with 300 ms debounce
// ─────────────────────────────────────────────────────────────────────────────

/** Per-document debounce timers. */
const debounceTimers = new Map<string, ReturnType<typeof setTimeout>>();

/**
 * The scanner no longer decides what is an error: problems come from `weave check`, the language's own parser, which the extension runs when a
 * file is opened or saved (see `src/check`). This only clears what this server showed before, so nothing from the scanner lingers.
 */
function validateDocument(doc: TextDocument): void {
    connection.sendDiagnostics({ uri: doc.uri, diagnostics: [] });
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
