import * as path from 'path';
import { OutlineRow } from './outlineModel';

/** One stage of the journey, as `weave next --format json` reports it. */
export interface Stage {
    number: number;
    id: string;
    title: string;
    state: 'done' | 'failing' | 'not-started' | 'skipped' | 'stale';
    detail: string;
    command?: string | null;
}

export interface Journey {
    stages: Stage[];
    primaryId?: string;
    /** The project's folder, as the engine found it. */
    root?: string;
}

/** Progress is asked for at most this often. */
export const PROGRESS_MIN_INTERVAL_MS = 3000;

const STATES = ['done', 'failing', 'not-started', 'skipped', 'stale'];
const ICONS: Record<Stage['state'], string> = {
    done: 'pass',
    failing: 'error',
    'not-started': 'circle-outline',
    skipped: 'debug-step-over',
    stale: 'history',
};
const WORDS: Record<Stage['state'], string> = {
    done: 'done',
    failing: 'failing',
    'not-started': 'not started',
    skipped: 'skipped',
    stale: 'stale',
};
/** A finished stage whose product is a file the person may want to read. */
const PRODUCT_FILES: Record<string, string> = { 'design-evaluation': 'EVAL_DESIGN.md' };

/** Reads the answer of `loom/next`; undefined when it is not a list of stages. */
export function parseJourney(answer: unknown): Journey | undefined {
    const result = (answer as { result?: { stages?: unknown; primary?: { id?: unknown }; projectRoot?: unknown } } | undefined)?.result;
    if (!result || !Array.isArray(result.stages) || result.stages.length === 0) {
        return undefined;
    }
    const stages: Stage[] = [];
    for (const raw of result.stages as Array<Record<string, unknown>>) {
        if (typeof raw.id !== 'string' || typeof raw.title !== 'string' || typeof raw.number !== 'number' || !STATES.includes(String(raw.state))) {
            return undefined;
        }
        stages.push({
            number: raw.number,
            id: raw.id,
            title: raw.title,
            state: raw.state as Stage['state'],
            detail: typeof raw.detail === 'string' ? raw.detail : '',
            command: typeof raw.command === 'string' ? raw.command : null,
        });
    }
    return {
        stages,
        primaryId: typeof result.primary?.id === 'string' ? result.primary.id : undefined,
        root: typeof result.projectRoot === 'string' ? result.projectRoot : undefined,
    };
}

/** The progress group of the Outline: one row per stage, the primary next step marked. */
export function progressRows(journey: Journey, projectRoot: string, ageSeconds = 0): OutlineRow {
    const finished = journey.stages.filter((s) => s.state === 'done' || s.state === 'skipped').length;
    const children = journey.stages.map((s): OutlineRow => {
        const isPrimary = s.id === journey.primaryId;
        const reason = s.detail ? `${s.state === 'done' ? '' : WORDS[s.state] + ': '}${s.detail}` : WORDS[s.state];
        const productFile = s.state === 'done' ? PRODUCT_FILES[s.id] : undefined;
        return {
            kind: 'stage',
            stageId: s.id,
            label: `${s.number}. ${s.title}`,
            description: (isPrimary ? 'next · ' : '') + reason,
            icon: ICONS[s.state],
            accessibleLabel: `${s.number}. ${s.title}, ${WORDS[s.state]}${s.detail ? ', ' + s.detail : ''}${isPrimary ? ', the next step' : ''}`,
            file: productFile ? path.join(projectRoot, productFile) : undefined,
            typeCommand: productFile ? undefined : s.command ?? undefined,
        };
    });
    const age = ageSeconds >= 5 ? ` (as of ${ageSeconds} s ago)` : '';
    return { kind: 'section', label: `Project progress: ${finished} of ${journey.stages.length} done or skipped${age}`, children };
}

/**
 * Asks for the project's progress, but not more than once in three seconds, and keeps the last
 * answer (with its age) while a new one is awaited or when the engine cannot answer.
 */
export class ProgressSource {
    private last: { journey: Journey; at: number } | undefined;
    private pending: Promise<void> | undefined;
    private requestedAt = Number.NEGATIVE_INFINITY;

    constructor(
        private readonly ask: () => Promise<unknown>,
        private readonly now: () => number,
        private readonly minIntervalMs = PROGRESS_MIN_INTERVAL_MS,
    ) {}

    async get(): Promise<{ journey: Journey; ageSeconds: number } | undefined> {
        if (!this.pending && this.now() - this.requestedAt >= this.minIntervalMs) {
            this.requestedAt = this.now();
            this.pending = this.ask().then(
                (answer) => {
                    const journey = parseJourney(answer);
                    if (journey) {
                        this.last = { journey, at: this.now() };
                    }
                },
                () => undefined,
            ).finally(() => {
                this.pending = undefined;
            });
            if (!this.last) {
                await this.pending;
            }
        }
        return this.last ? { journey: this.last.journey, ageSeconds: Math.round((this.now() - this.last.at) / 1000) } : undefined;
    }
}
