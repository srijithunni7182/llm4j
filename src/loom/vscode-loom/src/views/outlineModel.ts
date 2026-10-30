/**
 * What the Workflow Outline shows, as plain data: the view is never blank, so every workspace
 * state maps to rows that say what is there or what to do. It imports nothing from VS Code so it
 * can be tested without it.
 */

export type SymbolKind = 'agent' | 'workflow' | 'schedule' | 'routing';

export interface OutlineSymbol {
    kind: SymbolKind;
    name: string;
    /** 1-based line of the definition. */
    line: number;
}

export interface LoomFileFacts {
    path: string;
    /** The path shown in the tree, relative to the workspace folder. */
    relative: string;
    symbols: OutlineSymbol[];
}

export interface WorkspaceFacts {
    hasFolder: boolean;
    files: LoomFileFacts[];
    /** Files beyond the limit that were not read. */
    moreFiles?: number;
    /** The file in the active editor, when it is a Loom file. */
    active?: LoomFileFacts;
    error?: { message: string; fix: string };
    /** The project's progress group (from `loom/next`), or a message saying why there is none. */
    progress?: OutlineRow;
}

export interface OutlineRow {
    kind: 'message' | 'error' | 'section' | 'file' | 'symbol' | 'more' | 'stage';
    label: string;
    description?: string;
    /** Opens this file, at `line` when given. */
    file?: string;
    line?: number;
    /** A VS Code command the row runs when clicked. */
    command?: string;
    children?: OutlineRow[];
    collapsed?: boolean;
    /** The id of a theme icon to show. */
    icon?: string;
    /** For a progress stage: its id in `weave next`. */
    stageId?: string;
    /** What a screen reader says: the state in words, not only an icon. */
    accessibleLabel?: string;
    /** A weave command a click types into a terminal; the person presses Enter. */
    typeCommand?: string;
}

/** More files than this are not read; the tree says how many were left out. */
export const OUTLINE_FILE_LIMIT = 200;

const DEFINITIONS: Array<{ kind: SymbolKind; pattern: RegExp }> = [
    { kind: 'agent', pattern: /^agent\s+(\w+)/ },
    { kind: 'workflow', pattern: /^workflow\s+(\w+)/ },
    { kind: 'schedule', pattern: /^schedule\s+(\w+)/ },
    { kind: 'routing', pattern: /^routing\s+(\w+)/ },
];

/** The top-level definitions of a script, in the order they appear. */
export function symbolsOf(text: string): OutlineSymbol[] {
    const found: OutlineSymbol[] = [];
    text.split(/\r?\n/).forEach((line, index) => {
        for (const { kind, pattern } of DEFINITIONS) {
            const match = pattern.exec(line);
            if (match) {
                found.push({ kind, name: match[1], line: index + 1 });
                break;
            }
        }
    });
    return found;
}

function symbolRows(file: LoomFileFacts): OutlineRow[] {
    return file.symbols.map((s) => ({ kind: 'symbol', label: s.name, description: s.kind, file: file.path, line: s.line }));
}

function folderSection(facts: WorkspaceFacts, collapsed: boolean): OutlineRow {
    const children: OutlineRow[] = facts.files.map((f) => ({
        kind: 'file',
        label: f.relative,
        file: f.path,
        children: f.symbols.length > 0 ? symbolRows(f) : [{ kind: 'message', label: 'nothing defined yet', file: f.path }],
    }));
    if (facts.moreFiles && facts.moreFiles > 0) {
        children.push({ kind: 'more', label: `${facts.moreFiles} more not shown`, description: 'use Go to Symbol or the Explorer' });
    }
    return { kind: 'section', label: 'Workflows in this folder', children, collapsed };
}

/**
 * The rows for the workspace as it is. An empty list means the welcome content should show: a
 * folder with no Loom file has nothing to list, and the welcome view offers the first steps.
 */
export function outlineRows(facts: WorkspaceFacts): OutlineRow[] {
    if (facts.error) {
        return [{ kind: 'error', label: facts.error.message, description: facts.error.fix }];
    }
    if (!facts.hasFolder) {
        return [{ kind: 'message', label: 'Open a folder to see its workflows', command: 'vscode.openFolder' }];
    }
    if (facts.files.length === 0) {
        return [];
    }
    const progress = facts.progress ? [facts.progress] : [];
    if (facts.active) {
        return [...progress, { kind: 'section', label: 'This file', children: symbolRows(facts.active) }, folderSection(facts, true)];
    }
    return [...progress, folderSection(facts, false), { kind: 'message', label: 'Open a .loom file to see its steps' }];
}

/** Batches a burst of events (a save, a rename, many file changes) into one refresh. */
export class Debouncer {
    private pending: number | undefined;

    constructor(
        private readonly action: () => void,
        private readonly start: (fn: () => void) => number,
        private readonly cancel: (id: number) => void,
    ) {}

    poke(): void {
        if (this.pending !== undefined) {
            this.cancel(this.pending);
        }
        this.pending = this.start(() => {
            this.pending = undefined;
            this.action();
        });
    }
}
