import { samePath } from '../graph/paths';
import { bindingFor, workflowAt } from './queries';
import { Binding, before, describe, Position, SymbolIndex, SymType } from './symbols';
import { maskedLinePrefix, offsetOf, positionOf, stateAt } from './text';

export type CompletionKind = 'agent' | 'workflow' | 'tool' | 'classifier' | 'decision' | 'checkpoint' | 'variable' | 'field' | 'value' | 'task' | 'mcp' | 'knowledge' | 'routing';

export interface Suggestion {
    label: string;
    kind: CompletionKind;
    detail?: string;
    insertText?: string;
}

export interface Suggestions {
    items: Suggestion[];
    /** How many characters before the cursor the suggestion replaces (the word being typed). */
    replaceLength: number;
}

const WORD = '([\\w-]*)$';

function definitionsOfKind(index: SymbolIndex, kind: string, kindLabel: CompletionKind, detail?: (d: SymbolIndex['definitions'][number]) => string | undefined): Suggestion[] {
    const seen = new Set<string>();
    return index.definitions
        .filter((d) => d.kind === kind)
        .filter((d) => !seen.has(d.name) && !!seen.add(d.name))
        .map((d) => ({ label: d.name, kind: kindLabel, detail: detail?.(d) ?? d.detail ?? kind }));
}

/** The variables a workflow has made by a place in it (its parameters, and what earlier steps bound). */
export function variablesAt(index: SymbolIndex, file: string, at: Position): Binding[] {
    const workflow = workflowAt(index, file, at);
    if (!workflow) {
        return [];
    }
    const made = index.bindings.filter((b) => samePath(b.file, file) && b.scope === `workflow:${workflow}` && (b.role === 'param' || before(b.range.start, at)));
    const first = new Map<string, Binding>();
    for (const b of made) {
        if (!first.has(b.name)) {
            first.set(b.name, b);
        }
    }
    return [...first.values()];
}

/** The type reached by a dotted path whose root is a variable in scope. */
export function typeOfPath(index: SymbolIndex, file: string, at: Position, path: string): SymType | undefined {
    const [root, ...rest] = path.split('.');
    const binding = variablesAt(index, file, at).find((b) => b.name === root);
    if (!binding) {
        return undefined;
    }
    let type: SymType = bindingFor(index, binding.key)?.type ?? binding.type;
    for (const part of rest) {
        if (type.kind !== 'object' || !(part in type.fields)) {
            return { kind: 'unknown' };
        }
        type = type.fields[part];
    }
    return type;
}

function fieldsOf(type: SymType | undefined): Suggestion[] {
    if (!type || type.kind !== 'object') {
        return [];
    }
    return Object.entries(type.fields).map(([name, t]) => ({ label: name, kind: 'field' as const, detail: describe(t) }));
}

function variables(index: SymbolIndex, file: string, at: Position): Suggestion[] {
    return variablesAt(index, file, at).map((b) => ({ label: b.name, kind: 'variable' as const, detail: describe(bindingFor(index, b.key)?.type ?? b.type) }));
}

function distinctTasks(index: SymbolIndex): Suggestion[] {
    const names = new Set(index.references.filter((r) => r.kind === 'task').map((r) => r.to.slice('task:'.length)));
    return [...names].sort().map((label) => ({ label, kind: 'task' as const, detail: 'task' }));
}

/**
 * What can be written at the cursor, when the place is one the index knows about: an agent after `to`, a checkpoint after
 * `rewind to`, the fields of a variable after its dot, the values of an enum after `==`, a variable in a condition or a `{}`.
 * Every suggestion is a name in scope with the right kind, so nothing offered fails `check`. `undefined` means "not a place I
 * know": the caller offers what it always has. An empty list means "a place I know, with nothing to offer" (nothing completes from
 * an unknown type).
 */
export function suggest(index: SymbolIndex, file: string, text: string, at: Position): Suggestions | undefined {
    const offset = offsetOf(text, at);
    const state = stateAt(text, offset);
    if (state.inComment) {
        return undefined;
    }
    if (state.inString) {
        return inString(index, file, text, at, offset, state.stringStart);
    }
    const line = maskedLinePrefix(text, offset);

    const m = (re: RegExp): RegExpExecArray | null => re.exec(line);
    const words = (items: Suggestion[], typed: string): Suggestions => ({ items, replaceLength: typed.length });

    let r: RegExpExecArray | null;
    if ((r = m(new RegExp(`\\b(?:delegate|handoff)\\b.*\\bto\\s+${WORD}`)))) {
        return words(definitionsOfKind(index, 'agent', 'agent', (d) => (d.detail ? `agent · ${d.detail}` : 'agent')), r[1]);
    }
    if ((r = m(new RegExp(`\\bbroadcast\\b.*\\bto\\s*\\[(?:[^\\]]*,)?\\s*${WORD}`)))) {
        return words(definitionsOfKind(index, 'agent', 'agent'), r[1]);
    }
    if ((r = m(new RegExp(`\\brewind\\s+to\\s+${WORD}`)))) {
        const workflow = workflowAt(index, file, at);
        const found = index.definitions.filter((d) => d.kind === 'checkpoint' && d.scope === workflow);
        return words(found.map((d) => ({ label: d.name, kind: 'checkpoint' as const, detail: 'checkpoint' })), r[1]);
    }
    if ((r = m(new RegExp(`\\bcall\\s+${WORD}`)))) {
        return words(
            index.scopes
                .filter((s, i, all) => all.findIndex((x) => x.workflow === s.workflow) === i)
                .map((s) => ({ label: s.workflow, kind: 'workflow' as const, detail: `(${s.params.join(', ')})` })),
            r[1],
        );
    }
    if ((r = m(new RegExp(`\\bintentclassify\\s+${WORD}`)))) {
        return words(definitionsOfKind(index, 'classifier', 'classifier'), r[1]);
    }
    if ((r = m(new RegExp(`\\bdecide\\s+${WORD}`)))) {
        return words(definitionsOfKind(index, 'decision', 'decision'), r[1]);
    }
    if ((r = m(new RegExp(`\\brun\\s+${WORD}`)))) {
        return words(distinctTasks(index), r[1]);
    }
    if ((r = m(new RegExp(`\\b(?:tools|approve)\\s*:\\s*\\[(?:[^\\]]*,)?\\s*${WORD}`)))) {
        return words(definitionsOfKind(index, 'tool', 'tool'), r[1]);
    }
    if ((r = m(new RegExp(`\\bmcp_servers\\s*:\\s*\\[(?:[^\\]]*,)?\\s*${WORD}`)))) {
        return words(definitionsOfKind(index, 'mcp', 'mcp'), r[1]);
    }
    if ((r = m(new RegExp(`\\bknowledge\\s*:\\s*\\[(?:[^\\]]*,)?\\s*${WORD}`)))) {
        return words(definitionsOfKind(index, 'knowledge', 'knowledge'), r[1]);
    }
    if ((r = m(new RegExp(`\\brouting\\s*:\\s*${WORD}`)))) {
        return words(definitionsOfKind(index, 'routing', 'routing'), r[1]);
    }
    if ((r = m(new RegExp(`\\bsupervise\\s+${WORD}`)))) {
        return words(definitionsOfKind(index, 'agent', 'agent'), r[1]);
    }
    // the values of an enum, after == or !=
    if ((r = /(?:^|[^\w.])([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)\s*(?:==|!=)\s*("?)([\w-]*)$/.exec(line))) {
        return enumValues(index, file, at, r[1], r[2] === '"', r[3]);
    }
    // the fields of a variable, after its dot
    if ((r = /(?:^|[^\w.])([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)\.([A-Za-z_]\w*)?$/.exec(line))) {
        const type = typeOfPath(index, file, at, r[1]);
        if (!type) {
            return undefined; // not one of this workflow's variables: some other dotted thing
        }
        return { items: fieldsOf(type), replaceLength: (r[2] ?? '').length };
    }
    // a variable in a condition, a return, a loop's collection
    if ((r = m(new RegExp(`\\b(?:alt|until|when)\\s*\\(\\s*${WORD}`))) || (r = m(new RegExp(`\\breturn\\s+${WORD}`))) || (r = m(new RegExp(`\\bfor\\s+each\\s+\\S+\\s+in\\s+${WORD}`)))) {
        return words(variables(index, file, at), r[1]);
    }
    return undefined;
}

function enumValues(index: SymbolIndex, file: string, at: Position, path: string, quoted: boolean, typed: string): Suggestions | undefined {
    const type = typeOfPath(index, file, at, path);
    if (!type) {
        return undefined;
    }
    const values = type.kind === 'enum' || type.kind === 'yesno' ? type.values : [];
    return {
        items: values.map((v) => ({ label: v, kind: 'value' as const, detail: describe(type), insertText: quoted ? v : `"${v}"` })),
        replaceLength: typed.length + (quoted ? 1 : 0),
    };
}

/** Inside a string: the value being compared with an enum, or a `{...}` placeholder; ordinary words are left alone. */
function inString(index: SymbolIndex, file: string, text: string, at: Position, offset: number, stringStart: number): Suggestions | undefined {
    const inside = text.slice(Math.max(stringStart, 0), offset);
    const lineStart = text.lastIndexOf('\n', stringStart - 1) + 1;
    const compared = /(?:^|[^\w.])([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)\s*(?:==|!=)\s*$/.exec(text.slice(lineStart, stringStart));
    if (compared && /^"[\w-]*$/.test(inside)) {
        return enumValues(index, file, positionOf(text, stringStart), compared[1], true, inside.slice(1));
    }
    const open = inside.lastIndexOf('{');
    if (open < 0 || inside.indexOf('}', open) >= 0) {
        return undefined;
    }
    const typed = inside.slice(open + 1);
    if (!/^[\w.]*$/.test(typed)) {
        return undefined;
    }
    const dot = typed.lastIndexOf('.');
    if (dot < 0) {
        return { items: variables(index, file, at), replaceLength: typed.length };
    }
    const type = typeOfPath(index, file, at, typed.slice(0, dot));
    return type ? { items: fieldsOf(type), replaceLength: typed.length - dot - 1 } : undefined;
}

export { positionOf };
