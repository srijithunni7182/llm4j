import * as fs from 'fs';
import * as path from 'path';
import { Journey, Stage } from '../views/progressModel';

/**
 * The Guide's side of the editor, free of the editor API so it can be tested: the events the
 * editor records, which of them deserve a notice, and how often a notice may appear.
 */

/** One event, as `.loom/guide/events-editor.jsonl` and `events-server.jsonl` hold it. Identifiers only: never file contents or secrets. */
export interface GuideEvent {
    seq?: number;
    at?: string;
    kind: string;
    stage?: string;
    subject?: string;
    data?: Record<string, string>;
    /** Which file it came from: `editor` or `server`. */
    source?: string;
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

/** A line's event, or undefined for one that cannot be read (a torn last line, for example). */
function parse(line: string): GuideEvent | undefined {
    try {
        const e = JSON.parse(line);
        return e && typeof e === 'object' && !Array.isArray(e) ? (e as GuideEvent) : undefined;
    } catch {
        return undefined;
    }
}

/** The editor writes only this file; `weave guide-mcp` writes `events-server.jsonl`. One writer each, so no lock is shared. */
export const EDITOR_EVENTS = 'events-editor.jsonl';
export const SERVER_EVENTS = 'events-server.jsonl';
const SOURCES: Array<[string, string]> = [['editor', EDITOR_EVENTS], ['server', SERVER_EVENTS]];

/** Appends an event to the editor's events file, keeping the newest {@link MAX_EVENTS}. Append-only; a trim replaces the file whole. */
export function appendEvent(root: string, e: GuideEvent, now: Date): number {
    const folder = guideFolder(root);
    fs.mkdirSync(folder, { recursive: true });
    const file = path.join(folder, EDITOR_EVENTS);
    const lines = readLines(file);
    const last = lines.reduce((max, l) => Math.max(max, Number(parse(l)?.seq) || 0), 0);
    const event: GuideEvent = { seq: last + 1, at: now.toISOString(), kind: cut(e.kind) };
    if (e.stage) event.stage = cut(e.stage);
    if (e.subject) event.subject = cut(e.subject);
    if (e.data) event.data = Object.fromEntries(Object.entries(e.data).map(([k, v]) => [cut(k), cut(String(v))]));
    const line = JSON.stringify(event);
    if (lines.length + 1 > MAX_EVENTS) {
        const tmp = file + '.tmp';
        fs.writeFileSync(tmp, [...lines.slice(lines.length - MAX_EVENTS + 1), line].join('\n') + '\n');
        fs.renameSync(tmp, file);
    } else {
        // a torn last line has no newline: start a fresh line rather than glue this event onto it
        let torn = false;
        try {
            const size = fs.statSync(file).size;
            if (size > 0) {
                const fd = fs.openSync(file, 'r');
                const b = Buffer.alloc(1);
                fs.readSync(fd, b, 0, 1, size - 1);
                fs.closeSync(fd);
                torn = b[0] !== 0x0a;
            }
        } catch {
            torn = false;
        }
        fs.appendFileSync(file, (torn ? '\n' : '') + line + '\n');
    }
    return event.seq as number;
}

/** The events the Guide has not discussed yet, from both files. */
export function undiscussed(root: string): GuideEvent[] {
    const folder = guideFolder(root);
    let seen: Record<string, number> = {};
    try {
        seen = JSON.parse(fs.readFileSync(path.join(folder, 'discussed.json'), 'utf8'));
    } catch {
        seen = {};
    }
    const out: GuideEvent[] = [];
    for (const [source, name] of SOURCES) {
        const upTo = Number(seen[source]) || 0;
        for (const l of readLines(path.join(folder, name))) {
            const e = parse(l);
            if (e && (Number(e.seq) || 0) > upTo) out.push({ ...e, source });
        }
    }
    return out.sort((a, b) => String(a.at).localeCompare(String(b.at)));
}

/** The status bar text for the notes not yet discussed, or undefined when there are none. */
export function statusText(count: number): string | undefined {
    if (count <= 0) return undefined;
    return `$(comment-discussion) Guide: ${count} note${count === 1 ? '' : 's'}`;
}
