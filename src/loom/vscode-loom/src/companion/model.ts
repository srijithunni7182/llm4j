import * as fs from 'fs';
import * as path from 'path';
import { Journey, Stage } from '../views/progressModel';

/**
 * The Guide's side of the editor, free of the editor API so it can be tested: the events the
 * editor records, which of them deserve a notice, and how often a notice may appear.
 */

/** One event, as `.loom/guide/events.jsonl` holds it. Identifiers only: never file contents or secrets. */
export interface GuideEvent {
    seq?: number;
    at?: string;
    kind: string;
    stage?: string;
    subject?: string;
    data?: Record<string, string>;
}

export const MAX_EVENTS = 500;
const MAX_FIELD = 200;
/** At most one notice in this many milliseconds. */
export const NOTICE_SPACING_MS = 2 * 60 * 1000;

export function guideFolder(root: string): string {
    return path.join(root, '.loom', 'guide');
}

/** The events a new journey shows compared with the last one: one per stage whose state changed. */
export function stageChanges(previous: Journey | undefined, next: Journey): GuideEvent[] {
    if (!previous) {
        return [];
    }
    const before = new Map(previous.stages.map((s): [string, Stage] => [s.id, s]));
    const out: GuideEvent[] = [];
    for (const s of next.stages) {
        const was = before.get(s.id);
        if (was && was.state !== s.state) {
            out.push({ kind: 'stage-changed', stage: s.id, subject: s.title, data: { from: was.state, to: s.state } });
        }
    }
    return out;
}

/** The notice an event deserves, or undefined when it can wait for the next conversation. */
export function noticeFor(e: GuideEvent): string | undefined {
    const to = e.data?.to;
    if (e.kind === 'stage-changed' && to === 'failing') {
        return `The Guide has a note: "${e.subject ?? e.stage}" is now failing.`;
    }
    if (e.kind === 'stage-changed' && to === 'stale') {
        return `The Guide has a note: "${e.subject ?? e.stage}" is out of date after your change.`;
    }
    if (e.kind === 'stage-changed' && to === 'done' && (e.stage === 'preflight' || e.stage === 'real-run-read' || e.stage === 'real-evaluation')) {
        return `The Guide has a note on your first real result: "${e.subject ?? e.stage}" is done.`;
    }
    if (e.kind === 'claim-mismatch') {
        return `The Guide has a note: the coding agent said "${e.stage}" is ${e.subject ?? 'done'}, but the project says otherwise.`;
    }
    if (e.kind === 'checkpoint' && e.subject === 'skipped') {
        return `The Guide has a note: the coding agent skipped "${e.stage}".`;
    }
    return undefined;
}

/** Decides whether a notice may appear now: spaced out, never while the person types to the Guide, and never when switched off. */
export class NoticeGate {
    private last = -Infinity;

    constructor(private readonly now: () => number) {}

    allow(enabled: boolean, typing: boolean): boolean {
        if (!enabled || typing || this.now() - this.last < NOTICE_SPACING_MS) {
            return false;
        }
        this.last = this.now();
        return true;
    }
}

function cut(s: string): string {
    return s.length <= MAX_FIELD ? s : s.slice(0, MAX_FIELD) + '…';
}

function readLines(file: string): string[] {
    try {
        return fs.readFileSync(file, 'utf8').split('\n').filter((l) => l.trim() !== '');
    } catch {
        return [];
    }
}

/** Appends an event to the project's events file, keeping the newest {@link MAX_EVENTS}. */
export function appendEvent(root: string, e: GuideEvent, now: Date): number {
    const folder = guideFolder(root);
    fs.mkdirSync(folder, { recursive: true });
    const file = path.join(folder, 'events.jsonl');
    let lines = readLines(file);
    let last = 0;
    if (lines.length > 0) {
        try {
            last = Number(JSON.parse(lines[lines.length - 1]).seq) || 0;
        } catch {
            last = 0;
        }
    }
    const event: GuideEvent = { seq: last + 1, at: now.toISOString(), kind: cut(e.kind) };
    if (e.stage) event.stage = cut(e.stage);
    if (e.subject) event.subject = cut(e.subject);
    if (e.data) event.data = Object.fromEntries(Object.entries(e.data).map(([k, v]) => [cut(k), cut(String(v))]));
    lines.push(JSON.stringify(event));
    if (lines.length > MAX_EVENTS) {
        lines = lines.slice(lines.length - MAX_EVENTS);
    }
    fs.writeFileSync(file, lines.join('\n') + '\n');
    return event.seq as number;
}

/** The events the Guide has not discussed yet. */
export function undiscussed(root: string): GuideEvent[] {
    const folder = guideFolder(root);
    let seen = 0;
    try {
        seen = Number(JSON.parse(fs.readFileSync(path.join(folder, 'discussed.json'), 'utf8')).seq) || 0;
    } catch {
        seen = 0;
    }
    const out: GuideEvent[] = [];
    for (const l of readLines(path.join(folder, 'events.jsonl'))) {
        try {
            const e = JSON.parse(l) as GuideEvent;
            if ((e.seq ?? 0) > seen) out.push(e);
        } catch {
            // a damaged line is skipped
        }
    }
    return out;
}

/** The status bar text for the notes not yet discussed, or undefined when there are none. */
export function statusText(count: number): string | undefined {
    if (count <= 0) return undefined;
    return `$(comment-discussion) Guide: ${count} note${count === 1 ? '' : 's'}`;
}
