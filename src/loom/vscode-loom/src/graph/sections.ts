import { GraphResult, SectionInfo, WorkflowGraph } from './model';

const NUMBERED = /^(\s*)\/\/\s*(\d+)[.)]\s+(.*?)\s*$/;
const COMMENT = /^(\s*)\/\/\s?(.*?)\s*$/;
const MAX_SECTIONS = 99;
const MAX_HINT_LINES = 2;

/**
 * The numbered comments (`// 1. Look and research`) that split a workflow into sections, from the lines of its
 * source. Only the outermost ones count (the least-indented numbered comments), so numbering inside a block is
 * ignored. Plain comment lines straight under a heading, at the same indent, are its hint.
 * `fromLine` and `toLine` are 1-based and inclusive.
 */
export function parseSections(text: string, fromLine: number, toLine: number): SectionInfo[] {
    const lines = text.split(/\r?\n/);
    const found: Array<{ indent: number; at: number; n: number; title: string }> = [];
    for (let at = Math.max(1, fromLine); at <= Math.min(lines.length, toLine); at++) {
        const m = NUMBERED.exec(lines[at - 1]);
        if (m && m[3]) {
            found.push({ indent: m[1].replace(/\t/g, '    ').length, at, n: Number(m[2]), title: m[3] });
        }
    }
    if (!found.length) {
        return [];
    }
    const outer = Math.min(...found.map((f) => f.indent));
    return found.filter((f) => f.indent === outer).slice(0, MAX_SECTIONS).map((f) => {
        const hint: string[] = [];
        for (let next = f.at; next < lines.length && hint.length < MAX_HINT_LINES; next++) {
            const c = COMMENT.exec(lines[next]);
            if (!c || NUMBERED.test(lines[next]) || c[1].replace(/\t/g, '    ').length !== f.indent || !c[2]) {
                break;
            }
            hint.push(c[2]);
        }
        return { n: f.n, title: f.title, hint: hint.join(' '), line: f.at };
    });
}

/** The sections of every workflow of a result, read from the sources by `read` (undefined when a file cannot be read). */
export async function readSections(result: GraphResult, read: (file: string) => Promise<string | undefined>): Promise<Record<string, SectionInfo[]>> {
    const out: Record<string, SectionInfo[]> = {};
    const texts = new Map<string, string | undefined>();
    for (const wf of result.workflows) {
        if (wf.sections?.length || !wf.line) {
            continue;
        }
        if (!texts.has(wf.file)) {
            texts.set(wf.file, await read(wf.file).catch(() => undefined));
        }
        const text = texts.get(wf.file);
        if (text === undefined) {
            continue;
        }
        const next = result.workflows.filter((o) => o.file === wf.file && (o.line ?? 0) > wf.line!).map((o) => o.line as number);
        const sections = parseSections(text, wf.line + 1, next.length ? Math.min(...next) - 1 : Number.MAX_SAFE_INTEGER);
        if (sections.length) {
            out[sectionKey(wf)] = sections;
        }
    }
    return out;
}

export function sectionKey(wf: Pick<WorkflowGraph, 'name' | 'file'>): string {
    return `${wf.name}@${wf.file}`;
}
