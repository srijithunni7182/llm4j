import { EngineCancelledError, EngineStaleError } from '../engine/errors';
import { GraphAbortedError } from '../graph/errors';
import { Scheduler, TimerHandle } from '../graph/controller';
import { ReviewData } from './model';
import { ReviewFailed, ReviewSource, Tier } from './source';

export type ReviewStatus = 'loading' | 'running' | 'idle' | 'stale' | 'error';

/** What is known about one script's review. `review` and `graph` are the last good ones and stay while a new one runs. */
export interface ReviewState {
    file: string;
    status: ReviewStatus;
    message?: string;
    review?: ReviewData;
    graph?: unknown;
    /** The tier of the result shown. */
    tier?: Tier;
    /** Counts up with every result, so a listener can tell a new result from a repeated notice. */
    generation: number;
}

export interface ReviewSettings {
    /** When the review runs by itself: after a save, while typing (the validity lens only), or only when asked. */
    on: 'save' | 'type' | 'manual';
    lenses?: string;
    minSeverity?: string;
}

export interface ReviewControllerOptions {
    source: ReviewSource;
    scheduler: Scheduler;
    settings: () => ReviewSettings;
    /** Unsaved text of an open buffer, else undefined. */
    unsavedText: (file: string) => string | undefined;
    /** Wait this long after the last keystroke before the instant review. */
    typingMs?: number;
}

export const TYPING_DEBOUNCE_MS = 200;

/**
 * Keeps the review of each script current without ever showing it as more current than it is. It runs the review after a save, runs
 * only the validity lens while typing (when the setting says so), marks the result stale the moment the text changes, keeps the last
 * good result on screen while a new one runs or when one fails, and drops an answer that a newer request has replaced.
 */
export class ReviewController {
    private readonly states = new Map<string, ReviewState>();
    private readonly timers = new Map<string, TimerHandle>();
    private readonly running = new Map<string, AbortController>();
    private readonly listeners = new Set<(state: ReviewState) => void>();
    private disposed = false;

    constructor(private readonly options: ReviewControllerOptions) {}

    onDidChange(listener: (state: ReviewState) => void): { dispose(): void } {
        this.listeners.add(listener);
        return { dispose: () => this.listeners.delete(listener) };
    }

    state(file: string): ReviewState {
        return this.states.get(key(file)) ?? { file, status: 'loading', generation: 0 };
    }

    /** The panel was opened, or the person asked: review now, the whole thing. */
    request(file: string, tier: Tier = 'full'): Promise<void> {
        this.timers.get(key(file))?.cancel();
        this.timers.delete(key(file));
        return this.run(file, tier);
    }

    /** The text changed. The result on screen is marked stale at once; what runs next depends on the setting. */
    changed(file: string): void {
        if (this.disposed) {
            return;
        }
        const current = this.states.get(key(file));
        if (current?.review && current.status !== 'running') {
            this.set(file, { ...current, status: 'stale', message: undefined });
        }
        this.timers.get(key(file))?.cancel();
        this.timers.delete(key(file));
        if (this.options.settings().on !== 'type') {
            return;
        }
        this.timers.set(
            key(file),
            this.options.scheduler.after(this.options.typingMs ?? TYPING_DEBOUNCE_MS, () => {
                this.timers.delete(key(file));
                void this.run(file, 'instant');
            }),
        );
    }

    /** The file was saved: the full review, unless the person wants it only on request. */
    saved(file: string): void {
        if (this.disposed || this.options.settings().on === 'manual') {
            return;
        }
        void this.request(file, 'full');
    }

    /** The file was closed: nothing about it is kept. */
    closed(file: string): void {
        this.timers.get(key(file))?.cancel();
        this.timers.delete(key(file));
        this.running.get(key(file))?.abort();
        this.running.delete(key(file));
        this.states.delete(key(file));
    }

    dispose(): void {
        this.disposed = true;
        this.timers.forEach((t) => t.cancel());
        this.timers.clear();
        this.running.forEach((a) => a.abort());
        this.running.clear();
        this.listeners.clear();
    }

    // ---- running

    private async run(file: string, tier: Tier): Promise<void> {
        if (this.disposed) {
            return;
        }
        const k = key(file);
        this.running.get(k)?.abort();
        const abort = new AbortController();
        this.running.set(k, abort);
        const before = this.state(file);
        this.set(file, { ...before, status: before.review ? 'running' : 'loading', message: undefined });
        const settings = this.options.settings();
        try {
            const result = await this.options.source.review({
                file,
                tier,
                lenses: settings.lenses,
                minSeverity: settings.minSeverity,
                text: this.options.unsavedText(file),
                signal: abort.signal,
            });
            if (this.running.get(k) !== abort || this.disposed) {
                return;
            }
            const now = this.state(file);
            const review = tier === 'instant' ? mergeInstant(now.review, result.review) : result.review;
            this.set(file, { file, status: tier === 'instant' && now.tier === 'full' ? 'stale' : 'idle', review, graph: result.graph ?? now.graph, tier: tier === 'instant' && now.review ? now.tier : tier, generation: now.generation });
            this.running.delete(k);
        } catch (e) {
            if (this.running.get(k) !== abort || this.disposed) {
                return; // a newer request owns the state now
            }
            this.running.delete(k);
            if (e instanceof GraphAbortedError || e instanceof EngineCancelledError || e instanceof EngineStaleError) {
                return;
            }
            const now = this.state(file);
            this.set(file, { ...now, status: 'error', message: explain(e) });
        }
    }

    private set(file: string, state: ReviewState): void {
        const previous = this.states.get(key(file));
        const next = { ...state, file, generation: (previous?.generation ?? 0) + 1 };
        this.states.set(key(file), next);
        this.listeners.forEach((l) => l(next));
    }
}

function key(file: string): string {
    return file.replace(/\\/g, '/');
}

/** The validity findings of the quick review replace the earlier ones; every other lens keeps what the last full review said. */
export function mergeInstant(previous: ReviewData | undefined, instant: ReviewData): ReviewData {
    if (!previous) {
        return instant;
    }
    const kept = previous.findings.filter((f) => f.lens !== 'validity');
    const findings = [...instant.findings, ...kept];
    const count = (s: string): number => findings.filter((f) => f.severity === s).length;
    return {
        ...previous,
        findings,
        summary: { ...previous.summary, total: findings.length, high: count('high'), medium: count('medium'), low: count('low'), info: count('info') },
    };
}

function explain(e: unknown): string {
    if (e instanceof ReviewFailed) {
        return e.message;
    }
    return e instanceof Error ? e.message : String(e);
}
