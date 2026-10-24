import { typeOfPath, variablesAt } from './completion';
import { bindingFor, elementAt, referencesOf } from './queries';
import { contains, describe, Position, Range, SymbolIndex, SymType } from './symbols';
import { offsetOf, positionOf, stateAt } from './text';

export interface HoverInfo {
    markdown: string;
    range: Range;
}

function fieldLines(type: SymType): string[] {
    if (type.kind !== 'object') {
        return [];
    }
    return Object.entries(type.fields).map(([k, v]) => `- \`${k}\`: ${describe(v)}`);
}

function typeBlock(type: SymType): string {
    if (type.kind === 'object') {
        return ['**Fields**', ...fieldLines(type)].join('\n');
    }
    return `Type: \`${describe(type)}\``;
}

function line1(n: number): string {
    return `line ${n + 1}`;
}

/** The word-like path (`intent.kind`) around an offset, with the offsets it covers. */
function pathAround(text: string, offset: number): { path: string; start: number; end: number; rootEnd: number } | undefined {
    const isPart = (c: string | undefined): boolean => c !== undefined && /[\w.]/.test(c);
    let start = offset;
    while (start > 0 && isPart(text[start - 1])) {
        start--;
    }
    let end = offset;
    while (end < text.length && isPart(text[end])) {
        end++;
    }
    const path = text.slice(start, end);
    if (!/^[A-Za-z_]\w*(\.[A-Za-z_]\w*)+$/.test(path)) {
        return undefined;
    }
    return { path, start, end, rootEnd: start + path.indexOf('.') };
}

/** What to say about the thing under the cursor, or undefined when the index has nothing (the caller says what it always has). */
export function hoverAt(index: SymbolIndex, file: string, text: string, at: Position): HoverInfo | undefined {
    const offset = offsetOf(text, at);

    // a field of a variable: `intent.kind`, in code or in a {placeholder}
    const p = pathAround(text, offset);
    if (p) {
        const parts = p.path.split('.');
        let segmentStart = p.start;
        let at1 = parts.length - 1;
        for (let i = 0; i < parts.length; i++) {
            if (offset <= segmentStart + parts[i].length) {
                at1 = i;
                break;
            }
            segmentStart += parts[i].length + 1;
        }
        if (at1 >= 1) {
            const reached = parts.slice(0, at1 + 1).join('.');
            const type = typeOfPath(index, file, at, reached);
            if (type) {
                const name = parts[at1];
                const range: Range = { start: positionOf(text, segmentStart), end: positionOf(text, segmentStart + name.length) };
                const body = type.kind === 'unknown' ? `\`${name}\` is not a field this variable is known to have.` : `\`${name}\`: \`${describe(type)}\``;
                return { markdown: body, range };
            }
        }
    }

    // a string compared with an enum: the values it can be
    const state = stateAt(text, offset);
    if (state.inString) {
        const lineStart = text.lastIndexOf('\n', state.stringStart - 1) + 1;
        const head = text.slice(lineStart, state.stringStart);
        const cmp = /([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)\s*(?:==|!=)\s*$/.exec(head);
        if (cmp) {
            const type = typeOfPath(index, file, positionOf(text, state.stringStart), cmp[1]);
            if (type && (type.kind === 'enum' || type.kind === 'yesno')) {
                const close = text.indexOf('"', state.stringStart + 1);
                const literal = text.slice(state.stringStart + 1, close < 0 ? offset : close);
                const known = type.values.includes(literal);
                const lines = [`\`${cmp[1]}\` is one of: ${type.values.map((v) => `\`${v}\``).join(', ')}`];
                if (!known) {
                    lines.push('', `\`${literal}\` is not one of them, so this comparison is never true.`);
                }
                return { markdown: lines.join('\n'), range: { start: positionOf(text, state.stringStart), end: positionOf(text, close < 0 ? offset : close + 1) } };
            }
        }
    }

    const e = elementAt(index, file, at);
    if (!e) {
        return undefined;
    }
    if (e.kind === 'binding') {
        const b = bindingFor(index, e.key);
        const type = b?.type ?? e.type ?? { kind: 'unknown' as const };
        const lines = [`**variable** \`${e.name}\``, '', typeBlock(type)];
        if (b?.role === 'param') {
            lines.push('', 'A parameter of the workflow.');
        } else if (b?.producedBy) {
            lines.push('', `Made by \`${b.producedBy.kind}\` at ${line1(b.producedBy.range.start.line)}.`);
        }
        if (type.kind === 'unknown') {
            lines.push('', 'Its type is not known from the script, so nothing completes from it.');
        }
        return { markdown: lines.join('\n'), range: e.range };
    }
    const def = index.definitions.find((d) => d.key === e.key);
    if (!def) {
        if (e.kind === 'task') {
            return { markdown: `**task** \`${e.name}\`\n\nSupplied by the host; the script does not define it.`, range: e.range };
        }
        return e.resolved ? undefined : { markdown: `\`${e.name}\` is not defined in this script or its imports.`, range: e.range };
    }
    const uses = referencesOf(index, e.key, false).length;
    const lines = [`**${def.kind}** \`${def.name}\``];
    if (def.kind === 'agent') {
        if (def.detail) {
            lines.push('', `model \`${def.detail}\``);
        }
        const inside = def.extent;
        const tools = inside ? index.references.filter((r) => r.role === 'tool-use' && r.file === def.file && contains(inside, r.range.start)).map((r) => r.to.slice('tool:'.length)) : [];
        if (tools.length > 0) {
            lines.push('', `tools: ${[...new Set(tools)].map((t) => `\`${t}\``).join(', ')}`);
        }
        if (def.type) {
            lines.push('', typeBlock(def.type).replace('**Fields**', '**Replies with** (output_schema)'));
        }
    } else if (def.kind === 'workflow') {
        const scope = index.scopes.find((s) => s.workflow === def.name && s.file === def.file);
        lines.push('', `\`${def.name}(${scope?.params.join(', ') ?? ''})\``);
    } else if (def.kind === 'classifier' && def.type) {
        lines.push('', typeBlock(def.type).replace('**Fields**', '**Fills a record with**'));
    } else if (def.kind === 'decision' && def.type) {
        lines.push('', `choices: ${describe(def.type)}`);
    } else if (def.kind === 'prompt') {
        lines.push('', def.file);
    }
    lines.push('', `${uses} ${uses === 1 ? 'use' : 'uses'} · defined at ${line1(def.range.start.line)}`);
    return { markdown: lines.join('\n'), range: e.range };
}

export { variablesAt };
