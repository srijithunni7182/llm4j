import { samePath } from '../graph/paths';
import { RESERVED_WORDS } from './reserved';
import { Binding, contains, Definition, Position, Range, Reference, size, SymbolIndex, SymType } from './symbols';

/** A place in a file. */
export interface Location {
    file: string;
    range: Range;
}

/** The thing under a cursor: a definition, a use of one, or a variable. */
export interface Element {
    key: string;
    kind: string;
    name: string;
    /** `definition`, `binding`, or the role of the use (`delegate-target`, `use`, ...). */
    role: string;
    range: Range;
    file: string;
    resolved: boolean;
    type?: SymType;
}

function nameOfKey(key: string): string {
    const rest = key.slice(key.indexOf(':') + 1);
    return rest.includes('/') && !key.startsWith('file:') ? rest.slice(rest.lastIndexOf('/') + 1) : rest;
}

/** The innermost definition, use or variable at a place in a file. */
export function elementAt(index: SymbolIndex, file: string, at: Position): Element | undefined {
    let best: { element: Element; rank: number; area: number } | undefined;
    const offer = (element: Element, rank: number): void => {
        const area = size(element.range);
        if (!best || area < best.area || (area === best.area && rank < best.rank)) {
            best = { element, rank, area };
        }
    };
    for (const d of index.definitions) {
        if (samePath(d.file, file) && contains(d.range, at) && d.kind !== 'prompt') {
            offer({ key: d.key, kind: d.kind, name: d.name, role: 'definition', range: d.range, file: d.file, resolved: true }, 0);
        }
    }
    for (const b of index.bindings) {
        if (samePath(b.file, file) && contains(b.range, at)) {
            offer({ key: b.key, kind: 'binding', name: b.name, role: 'binding', range: b.range, file: b.file, resolved: true, type: b.type }, 1);
        }
    }
    for (const r of index.references) {
        if (samePath(r.file, file) && contains(r.range, at)) {
            offer({ key: r.to, kind: r.kind, name: nameOfKey(r.to), role: r.role, range: r.range, file: r.file, resolved: r.resolved }, 2);
        }
    }
    const found = best as { element: Element } | undefined;
    if (found && found.element.kind === 'binding') {
        found.element.type = bindingFor(index, found.element.key)?.type;
    }
    return found?.element;
}

/** The binding that makes a variable: its first place in the file. */
export function bindingFor(index: SymbolIndex, key: string): Binding | undefined {
    return index.bindings.filter((b) => b.key === key).sort(byPlace)[0];
}

function byPlace(a: { file: string; range: Range }, b: { file: string; range: Range }): number {
    return a.file.localeCompare(b.file) || a.range.start.line - b.range.start.line || a.range.start.character - b.range.start.character;
}

const ZERO: Range = { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } };

// ---- definition and references

/** Where an element is defined: nothing for what the script does not define (a task, a typo). */
export function definitionOf(index: SymbolIndex, element: Element): Location[] {
    if (element.kind === 'file') {
        return element.resolved ? [{ file: element.key.slice('file:'.length), range: ZERO }] : [];
    }
    if (element.kind === 'binding') {
        const b = bindingFor(index, element.key);
        return b ? [{ file: b.file, range: b.range }] : [];
    }
    return index.definitions.filter((d) => d.key === element.key).map((d) => ({ file: d.file, range: d.range }));
}

/** Every use of a definition or variable, and (when asked) the definition itself. */
export function referencesOf(index: SymbolIndex, key: string, includeDeclaration: boolean): Location[] {
    const out: Location[] = index.references.filter((r) => r.to === key).map((r) => ({ file: r.file, range: r.range }));
    if (includeDeclaration) {
        index.definitions.filter((d) => d.key === key && d.kind !== 'prompt').forEach((d) => out.push({ file: d.file, range: d.range }));
        index.bindings.filter((b) => b.key === key).forEach((b) => out.push({ file: b.file, range: b.range }));
    }
    return out.sort(byPlace);
}

// ---- rename

export interface TextEdit {
    file: string;
    range: Range;
    newText: string;
}

export interface FileRename {
    from: string;
    to: string;
}

export type RenamePlan = { ok: true; edits: TextEdit[]; renames: FileRename[]; placeholder: string } | { ok: false; reason: string };

/** What the plan needs to know about the files, supplied by the editor (and by tests). */
export interface FileFacts {
    isWritable(file: string): boolean;
    exists(file: string): boolean;
}

const NAME = /^[A-Za-z_][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)*$/;
const VARIABLE = /^[A-Za-z_][A-Za-z0-9_]*$/;

/** Can the element under the cursor be renamed, and what is its name now? */
export function prepareRename(index: SymbolIndex, file: string, at: Position): { range: Range; placeholder: string } | { reason: string } {
    const e = elementAt(index, file, at);
    if (!e) {
        return { reason: 'Nothing here can be renamed.' };
    }
    const why = notRenameable(index, e);
    return why ? { reason: why } : { range: e.range, placeholder: e.name };
}

function notRenameable(index: SymbolIndex, e: Element): string | undefined {
    if (e.kind === 'file') {
        return 'An import is renamed by renaming the file in the Explorer.';
    }
    if (e.kind === 'task') {
        return `"${e.name}" is a task supplied by the host, not defined in the script; rename it in the code that registers it.`;
    }
    if (!e.resolved || (e.kind !== 'binding' && index.definitions.every((d) => d.key !== e.key))) {
        return `"${e.name}" is not defined in this script or its imports, so it cannot be renamed; define it first.`;
    }
    if (e.kind === 'prompt' && e.name.includes('@')) {
        return 'A versioned prompt (name@version) is renamed by renaming its folder and file in the Explorer.';
    }
    return undefined;
}

/**
 * Every edit a rename needs, or the reason it cannot be done. All or nothing: if any file that would change cannot be written, an
 * import cannot be found (so some uses may be missed), or the new name is taken or not a name, no edit is returned.
 */
export function planRename(index: SymbolIndex, file: string, at: Position, newName: string, facts: FileFacts): RenamePlan {
    const e = elementAt(index, file, at);
    if (!e) {
        return { ok: false, reason: 'Nothing here can be renamed.' };
    }
    const why = notRenameable(index, e);
    if (why) {
        return { ok: false, reason: why };
    }
    const name = newName.trim();
    const pattern = e.kind === 'binding' ? VARIABLE : NAME;
    if (!pattern.test(name)) {
        return { ok: false, reason: `"${name}" is not a valid name here: use letters, digits and "_"${e.kind === 'binding' ? '' : ' (and "-" between them)'}, starting with a letter or "_".` };
    }
    if (RESERVED_WORDS.has(name)) {
        return { ok: false, reason: `"${name}" is a Loom keyword and cannot be used as a name.` };
    }
    if (name === e.name) {
        return { ok: false, reason: 'The new name is the same as the old one.' };
    }
    const newKey = e.key.slice(0, e.key.length - e.name.length) + name;
    if (index.definitions.some((d) => d.key === newKey) || index.bindings.some((b) => b.key === newKey)) {
        return { ok: false, reason: `"${name}" is already used by another ${e.kind === 'binding' ? 'variable in this workflow' : e.kind}.` };
    }
    const unresolvedImport = index.references.find((r) => r.kind === 'file' && !r.resolved);
    if (unresolvedImport) {
        return { ok: false, reason: `An import cannot be found (${unresolvedImport.to.slice('file:'.length)}), so some uses of "${e.name}" might be missed. Fix the import first.` };
    }

    const sites: Location[] = referencesOf(index, e.key, true);
    const edits: TextEdit[] = sites.map((s) => ({ file: s.file, range: s.range, newText: name }));
    const renames: FileRename[] = [];
    if (e.kind === 'prompt') {
        const def = index.definitions.find((d) => d.key === e.key);
        if (def) {
            const dir = def.file.slice(0, Math.max(def.file.lastIndexOf('/'), def.file.lastIndexOf('\\')) + 1);
            const to = `${dir}${name}.md`;
            if (facts.exists(to)) {
                return { ok: false, reason: `The file ${to} already exists.` };
            }
            renames.push({ from: def.file, to });
        }
    }
    const blocked = [...new Set([...edits.map((x) => x.file), ...renames.map((r) => r.from)])].filter((f) => !facts.isWritable(f));
    if (blocked.length > 0) {
        return { ok: false, reason: `Nothing was changed: ${blocked.join(', ')} cannot be written (read-only).` };
    }
    return { ok: true, edits, renames, placeholder: e.name };
}

// ---- document symbols

export interface OutlineItem {
    name: string;
    kind: string;
    detail?: string;
    range: Range;
    selection: Range;
    children: OutlineItem[];
}

const TOP_LEVEL = new Set(['agent', 'workflow', 'tool', 'classifier', 'persona', 'provider', 'decision', 'mcp', 'knowledge', 'routing']);

/** The outline of one file: its blocks, and a workflow's checkpoints and variables inside it. */
export function outline(index: SymbolIndex, file: string): OutlineItem[] {
    const items: OutlineItem[] = [];
    const defs = index.definitions.filter((d) => samePath(d.file, file) && TOP_LEVEL.has(d.kind)).sort(byPlace);
    for (const d of defs) {
        const item: OutlineItem = { name: d.name, kind: d.kind, detail: d.detail, range: d.extent ?? d.range, selection: d.range, children: [] };
        if (d.kind === 'workflow') {
            const inside = (r: Range): boolean => contains(item.range, r.start);
            index.definitions
                .filter((c) => c.kind === 'checkpoint' && samePath(c.file, file) && inside(c.range) && c.scope === d.name)
                .forEach((c) => item.children.push({ name: c.name, kind: 'checkpoint', range: c.range, selection: c.range, children: [] }));
            const seen = new Set<string>();
            index.bindings
                .filter((b) => samePath(b.file, file) && b.scope === `workflow:${d.name}` && inside(b.range))
                .sort(byPlace)
                .forEach((b) => {
                    if (!seen.has(b.key)) {
                        seen.add(b.key);
                        item.children.push({ name: b.name, kind: 'variable', detail: b.role === 'param' ? 'parameter' : undefined, range: b.range, selection: b.range, children: [] });
                    }
                });
        }
        items.push(item);
    }
    return items;
}

// ---- scope

/** The workflow whose text holds a place in a file. */
export function workflowAt(index: SymbolIndex, file: string, at: Position): string | undefined {
    const inside = index.scopes.filter((s) => samePath(s.file, file) && contains(s.range, at)).sort((a, b) => size(a.range) - size(b.range));
    return inside[0]?.workflow;
}

// ---- signature help

export interface Signature {
    label: string;
    parameters: string[];
    active: number;
}

/**
 * The parameters of the workflow a `call Name(` is typing, with the one under the cursor. `before` is the text from the start of the
 * statement to the cursor (it may span lines).
 */
export function signatureAt(index: SymbolIndex, before: string): Signature | undefined {
    const m = /\bcall\s+([A-Za-z_][\w-]*)\s*\(([^)]*)$/.exec(before);
    if (!m) {
        return undefined;
    }
    const scope = index.scopes.find((s) => s.workflow === m[1]);
    if (!scope) {
        return undefined;
    }
    const typed = m[2];
    const named = /([A-Za-z_]\w*)\s*=\s*[^,]*$/.exec(typed);
    const active = named && scope.params.includes(named[1]) ? scope.params.indexOf(named[1]) : Math.min(typed.split(',').length - 1, Math.max(scope.params.length - 1, 0));
    return { label: `${scope.workflow}(${scope.params.join(', ')})`, parameters: scope.params, active };
}

export function isTopLevelKind(kind: string): boolean {
    return TOP_LEVEL.has(kind);
}

export type { Definition, Reference };
