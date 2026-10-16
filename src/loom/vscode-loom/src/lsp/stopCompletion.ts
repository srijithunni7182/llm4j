/**
 * What to offer while someone writes what happens when an agent is stopped: the fields of `_stop`, the checkpoints a `rewind to` may name (only
 * those the script declared before it, and `start`), and the outcomes at the start of a line inside an `on_stopped { }` handler.
 *
 * Pure, like ladderCompletion.ts, so it is tested without the language-server connection. It answers `undefined` when the cursor is not somewhere
 * it knows about, and the server then offers what it always offered.
 */

export type StopCompletionKind = 'field' | 'checkpoint' | 'outcome';

export interface StopCompletion {
    label: string;
    kind: StopCompletionKind;
    detail: string;
}

const FIELDS: Array<[string, string]> = [
    ['reason', 'why it was stopped: user, operator, timeout, budget, limit, sibling, supervision, safety or shutdown'],
    ['note', "the words of whoever stopped it"],
    ['kind', 'cancel or wrap_up'],
    ['phase', 'where the agent was: before_start, model_call, between_tools or between_steps'],
    ['partial', 'the last thing the agent said'],
    ['changed', 'yes if the agent may already have changed something outside the run, otherwise no'],
    ['origin', 'who or what asked for the stop'],
];

const OUTCOMES: Array<[string, string]> = [
    ['use partial', 'keep what the agent had, marked as incomplete'],
    ['skip', 'the step produces nothing and the run goes on'],
    ['discard', 'drop the step quietly'],
    ['retry', 'run the step again (refused if the agent may have changed something)'],
    ['suspend', 'pause the run; the step starts again on resume'],
    ['conclude', 'end the run'],
    ['ask', 'put it to a person'],
    ['rewind to', 'go back to a declared checkpoint'],
    ['alt', 'choose by a condition on _stop'],
];

/** True when the offset is inside an `on_stopped { ... }` block that is still open. */
function insideHandler(before: string): boolean {
    const re = /\bon_stopped\s*\{/g;
    let last = -1;
    let m: RegExpExecArray | null;
    while ((m = re.exec(before)) !== null) {
        last = m.index + m[0].length;
    }
    if (last < 0) {
        return false;
    }
    let depth = 1;
    let inString = false;
    for (let i = last; i < before.length; i++) {
        const c = before[i];
        if (c === '"') {
            inString = !inString;
        } else if (!inString && c === '{') {
            depth++;
        } else if (!inString && c === '}') {
            depth--;
            if (depth === 0) {
                return false;
            }
        }
    }
    return true;
}

export function stopCompletions(text: string, offset: number): StopCompletion[] | undefined {
    const before = text.slice(0, offset);
    const line = before.slice(before.lastIndexOf('\n') + 1);

    if (/\b_stop\.\w*$/.test(line)) {
        return FIELDS.map(([label, detail]) => ({ label, kind: 'field', detail }));
    }

    if (/\brewind\s+to\s+\w*$/.test(line)) {
        const names = new Set<string>(['start']);
        const re = /\bcheckpoint\s+(\w+)/g;
        let m: RegExpExecArray | null;
        const declared = before.slice(0, before.length - line.length);
        while ((m = re.exec(declared)) !== null) {
            names.add(m[1]);
        }
        return [...names].map((label) => ({
            label,
            kind: 'checkpoint',
            detail: label === 'start' ? 'the point before the first statement' : 'a checkpoint declared earlier',
        }));
    }

    if (/^\s*\w*$/.test(line) && insideHandler(before)) {
        return OUTCOMES.map(([label, detail]) => ({ label, kind: 'outcome', detail }));
    }
    return undefined;
}
