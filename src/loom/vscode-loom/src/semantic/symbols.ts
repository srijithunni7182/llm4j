/** The semantic index `weave symbols` / `loom/symbols` answers (docs/serve/PROTOCOL.md), as the providers read it. */

export interface Position {
    line: number;
    character: number;
}

export interface Range {
    start: Position;
    end: Position;
}

export type SymType =
    | { kind: 'string' | 'number' | 'boolean' | 'unknown' }
    | { kind: 'yesno'; values: string[] }
    | { kind: 'enum'; values: string[] }
    | { kind: 'object'; fields: Record<string, SymType> }
    | { kind: 'list'; of: SymType };

export interface Definition {
    key: string;
    kind: string;
    name: string;
    file: string;
    range: Range;
    scope?: string;
    detail?: string;
    extent?: Range;
    /** What it produces: an agent's output schema, a classifier's record, a decision's choices. */
    type?: SymType;
}

export interface Reference {
    to: string;
    kind: string;
    role: string;
    file: string;
    range: Range;
    resolved: boolean;
}

export interface Binding {
    key: string;
    name: string;
    file: string;
    range: Range;
    scope: string;
    role: string;
    producedBy?: { kind: string; range: Range };
    type: SymType;
}

export interface Scope {
    workflow: string;
    file: string;
    params: string[];
    range: Range;
}

export interface Problem {
    file: string;
    line: number;
    message: string;
}

export interface SymbolIndex {
    version: number;
    entry: string;
    partial: boolean;
    definitions: Definition[];
    references: Reference[];
    bindings: Binding[];
    scopes: Scope[];
    problems: Problem[];
}

export const SUPPORTED_INDEX_VERSION = 1;

/** The index in an engine answer, or undefined when it is not one this extension understands. */
export function parseIndex(result: unknown): SymbolIndex | undefined {
    if (typeof result !== 'object' || result === null) {
        return undefined;
    }
    const r = result as Partial<SymbolIndex>;
    if (r.version !== SUPPORTED_INDEX_VERSION || !Array.isArray(r.definitions) || !Array.isArray(r.references) || !Array.isArray(r.bindings)) {
        return undefined;
    }
    return {
        version: r.version,
        entry: r.entry ?? '',
        partial: r.partial === true,
        definitions: r.definitions,
        references: r.references,
        bindings: r.bindings,
        scopes: Array.isArray(r.scopes) ? r.scopes : [],
        problems: Array.isArray(r.problems) ? r.problems : [],
    };
}

// ---- positions

export function before(a: Position, b: Position): boolean {
    return a.line < b.line || (a.line === b.line && a.character < b.character);
}

/** True when `p` is in the range, the end included (a cursor right after a word is on the word). */
export function contains(r: Range, p: Position): boolean {
    return !before(p, r.start) && !before(r.end, p);
}

export function sameRange(a: Range, b: Range): boolean {
    return a.start.line === b.start.line && a.start.character === b.start.character && a.end.line === b.end.line && a.end.character === b.end.character;
}

/** How big a range is, for choosing the innermost of several that contain a place. */
export function size(r: Range): number {
    return (r.end.line - r.start.line) * 100000 + (r.end.character - r.start.character);
}

/** The text a type shows as, for hovers and completion details. */
export function describe(type: SymType): string {
    switch (type.kind) {
        case 'enum':
            return type.values.join(' | ');
        case 'yesno':
            return 'yes | no';
        case 'object':
            return `{ ${Object.entries(type.fields)
                .map(([k, v]) => `${k}: ${describe(v)}`)
                .join(', ')} }`;
        case 'list':
            return `list<${describe(type.of)}>`;
        default:
            return type.kind;
    }
}
