/**
 * What to offer while someone types in a ladder: the ladders of the document after `ladder:`, the rungs after `ceiling:` and `slip to`, the measures and
 * the fields of a case inside the brackets of a collection method, the methods of a collection after a dot, and the words of a rung.
 *
 * Pure, with no dependency on the language-server connection, so it can be tested directly (like parseDocument.ts). It answers `undefined` when the cursor
 * is not somewhere it knows about, and the server then offers what it always offered.
 */

export type LadderCompletionKind = 'ladder' | 'rung' | 'measure' | 'method' | 'field' | 'keyword' | 'mode';

export interface LadderCompletion {
    label: string;
    kind: LadderCompletionKind;
    detail: string;
}

const COLLECTION_METHODS: Array<[string, string]> = [
    ['where', 'the cases for which a measure or condition holds'],
    ['last', 'the latest n cases: last(50)'],
    ['since', 'the cases of the last stretch of time: since(30d)'],
    ['between', 'the cases between two ages: between(30d, 7d)'],
    ['by', 'group the cases by a value: by(tier)'],
    ['count', 'how many cases (of those for which a measure holds)'],
    ['rate', 'the share of cases for which a measure holds, with its 95% interval'],
    ['mean', 'the average of a measure'],
    ['median', 'the middle value of a measure'],
    ['min', 'the smallest value of a measure'],
    ['max', 'the largest value of a measure'],
    ['sum', 'the total of a measure'],
    ['quantile', 'a quantile of a measure: quantile(edit_size, 0.9)'],
    ['any', 'whether any case satisfies a measure'],
    ['all', 'whether every case satisfies a measure'],
];

const COLLECTION_FIELDS: Array<[string, string]> = [
    ['count', 'the number of cases'],
    ['n', 'the number of cases'],
    ['span', 'the time from the first case to the last'],
    ['age', 'how long ago the first case was'],
];

const RATIO_FIELDS: Array<[string, string]> = [
    ['value', 'the share itself'],
    ['lower95', 'a cautious estimate: the lower end of the 95% interval'],
    ['upper95', 'an optimistic estimate: the upper end of the 95% interval'],
    ['n', 'the number of cases behind it'],
];

const CASE_FIELDS: Array<[string, string]> = [
    ['id', 'the case'], ['at', 'when it happened'], ['scope', 'the group it belongs to'], ['epoch', 'the epoch of the agent'], ['level', 'the rung it ran at'],
    ['proposal', 'what the agent proposed: .choice .value .reasoning .confidence'], ['response', 'what the person did: .kind .choice .final .by'],
    ['outcome', 'the latest later fact'], ['outcomes', 'every later fact, by name: outcomes.reversed'], ['shown', 'the person saw the proposal'],
    ['blind', 'the person did not see the proposal'], ['review_time', 'how long the person took'], ['latency', 'how long the person took'],
];

/** The measures a script gets from {@code import "loom/autonomy"}. */
const STANDARD_MEASURES: Array<[string, string]> = [
    ['accepted', 'the person accepted the proposal'], ['edited', 'the person edited it'], ['rejected', 'the person rejected it'],
    ['agrees', 'the person chose what the agent proposed'], ['edit_size', 'how far the person moved the proposal, 0 to 1'],
    ['untouched', 'accepted, or edited by under 2%'], ['reversed', 'a later fact said the verdict was reversed'],
];

const MODES: Array<[string, string]> = [
    ['blind', 'a person answers without seeing the proposal'], ['confirm', 'a person sees the proposal and accepts, edits or rejects it'],
    ['autonomous', 'the proposal takes effect without asking'],
];

const RUNG_WORDS: Array<[string, string]> = [
    ['mode:', 'blind, confirm or autonomous'], ['climb when', 'the condition to move up into this rung'], ['slip to', 'the rung to drop to, and when'],
    ['slip when', 'the condition to drop to the rung below'], ['sample', 'sample 5% blind'], ['escalate when', 'cases that always go to a person'],
];

/** Methods whose brackets take a measure or a condition about one case. */
const PER_CASE = ['where', 'count', 'rate', 'mean', 'median', 'min', 'max', 'sum', 'any', 'all', 'by', 'quantile'];

interface Ladder {
    name: string;
    start: number;
    end: number;
    rungs: string[];
}

/** The top-level `ladder Name { … }` blocks: where they are and which rungs they hold. */
function ladders(text: string): Ladder[] {
    const out: Ladder[] = [];
    const re = /(^|\n)\s*ladder\s+(\w[\w-]*)\s*(\([^)]*\))?\s*\{/g;
    let m: RegExpExecArray | null;
    while ((m = re.exec(text)) !== null) {
        const open = m.index + m[0].length - 1;
        const end = matching(text, open);
        const body = text.slice(open, end);
        const rungs: string[] = [];
        const rungRe = /\brung\s+(\w[\w-]*)/g;
        let r: RegExpExecArray | null;
        while ((r = rungRe.exec(body)) !== null) rungs.push(r[1]);
        out.push({ name: m[2], start: open, end, rungs });
    }
    return out;
}

/** The index of the brace that closes the one at `open`, skipping strings and comments; the end of the text when it is not closed. */
function matching(text: string, open: number): number {
    let depth = 0;
    for (let i = open; i < text.length; i++) {
        const c = text[i];
        if (c === '"') {
            i++;
            while (i < text.length && text[i] !== '"' && text[i] !== '\n') i += text[i] === '\\' ? 2 : 1;
        } else if (c === '/' && text[i + 1] === '/') {
            while (i < text.length && text[i] !== '\n') i++;
        } else if (c === '{') {
            depth++;
        } else if (c === '}') {
            depth--;
            if (depth === 0) return i;
        }
    }
    return text.length;
}

function measures(text: string): string[] {
    const out: string[] = [];
    const re = /(^|\n)\s*measure\s+(\w+)\s*\(/g;
    let m: RegExpExecArray | null;
    while ((m = re.exec(text)) !== null) out.push(m[2]);
    return out;
}

/** The block `decision Name { … }` or `agent Name { … }` that holds the offset, or undefined. */
function enclosing(text: string, offset: number, word: 'decision' | 'agent'): { start: number; end: number } | undefined {
    const re = new RegExp('(^|\\n)\\s*' + word + '\\s+\\w[\\w-]*\\s*\\{', 'g');
    let m: RegExpExecArray | null;
    while ((m = re.exec(text)) !== null) {
        const open = m.index + m[0].length - 1;
        const end = matching(text, open);
        if (offset > open && offset <= end) return { start: open, end };
    }
    return undefined;
}

function remembered(text: string): string[] {
    const out: string[] = [];
    const re = /\bremember\s*:\s*([^\n]*)/g;
    let m: RegExpExecArray | null;
    while ((m = re.exec(text)) !== null) {
        for (const part of m[1].split(',')) {
            const name = part.trim().split(':')[0].trim();
            if (/^\w+$/.test(name)) out.push(name);
        }
    }
    const group = /\bgroup cases by\s*:\s*(\w+)/g;
    while ((m = group.exec(text)) !== null) out.push(m[1]);
    return out;
}

const item = (label: string, kind: LadderCompletionKind, detail: string): LadderCompletion => ({ label, kind, detail });

function unique(items: LadderCompletion[]): LadderCompletion[] {
    const seen = new Set<string>();
    return items.filter(i => (seen.has(i.label) ? false : (seen.add(i.label), true)));
}

/** The measures a condition may name: the ones the script writes, the standard ones when they are imported, and `dangerous` when a decision declares mistakes. */
function measureItems(text: string): LadderCompletion[] {
    const out = measures(text).map(n => item(n, 'measure', 'a measure of this script'));
    if (/\bimport\s+"loom\/autonomy"/.test(text)) for (const [n, d] of STANDARD_MEASURES) out.push(item(n, 'measure', d + ' (loom/autonomy)'));
    if (/\bdangerous mistake\s*:/.test(text)) out.push(item('dangerous', 'measure', 'the agent proposed what a declared dangerous mistake names and the person decided the opposite'));
    return out;
}

function caseItems(text: string): LadderCompletion[] {
    return [...remembered(text).map(n => item(n, 'field', 'a value the decision remembers')), ...CASE_FIELDS.map(([n, d]) => item(n, 'field', d))];
}

/** Completions at `offset` of `text`, or undefined when the cursor is not in a place this knows about. */
export function ladderCompletions(text: string, offset: number): LadderCompletion[] | undefined {
    const lineStart = text.lastIndexOf('\n', offset - 1) + 1;
    const before = text.slice(lineStart, offset);
    const all = ladders(text);
    const inside = all.find(l => offset > l.start && offset <= l.end);

    // ladder: Name  (in a decision or an agent)
    if (/\bladder\s*:\s*\w*$/.test(before)) return all.map(l => item(l.name, 'ladder', l.rungs.length + ' rungs: ' + l.rungs.join(', ')));

    // ceiling: rung  (the rungs of the ladder the same decision or agent binds, else of every ladder)
    if (/\bceiling\s*:\s*\w*$/.test(before)) {
        const block = enclosing(text, offset, 'decision') || enclosing(text, offset, 'agent');
        const bound = block ? /\bladder\s*:\s*(\w[\w-]*)/.exec(text.slice(block.start, block.end)) : null;
        const mine = bound ? all.filter(l => l.name === bound[1]) : all;
        return unique((mine.length ? mine : all).flatMap(l => l.rungs.map(r => item(r, 'rung', 'a rung of ' + l.name))));
    }

    if (!inside) return undefined;

    // slip to rung
    if (/\bslip\s+to\s+\w*$/.test(before)) return inside.rungs.map(r => item(r, 'rung', 'a rung of ' + inside.name));
    // mode: blind | confirm | autonomous
    if (/\bmode\s*:\s*\w*$/.test(before)) return MODES.map(([n, d]) => item(n, 'mode', d));

    // inside the brackets of a collection method: a measure, or a field of the case
    const call = /\.(\w+)\(\s*[\w\s,.]*$/.exec(before);
    if (call && PER_CASE.includes(call[1])) return unique([...measureItems(text), ...caseItems(text)]);

    // case.<field>
    if (/\bcase\.\w*$/.test(before)) return caseItems(text);

    // <something>.<method or field>
    const dot = /([\w)]+)\.(\w*)$/.exec(before);
    if (dot) {
        if (dot[1].endsWith(')') && /\.(rate)\([^)]*\)\.\w*$/.test(before)) return RATIO_FIELDS.map(([n, d]) => item(n, 'field', d));
        if (/\bhistory\b|\)\s*\.\w*$/.test(before)) {
            return [...COLLECTION_METHODS.map(([n, d]) => item(n, 'method', d)), ...COLLECTION_FIELDS.map(([n, d]) => item(n, 'field', d))];
        }
    }

    // the start of a line in a rung: the words a rung has
    if (/^\s*\w*$/.test(before)) return RUNG_WORDS.map(([n, d]) => item(n, 'keyword', d));

    return undefined;
}
