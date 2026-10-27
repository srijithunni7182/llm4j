import { createHash } from 'crypto';

/** Where the notice remembers what it has said: VS Code's workspace state in the extension, a map in tests. */
export interface Memory {
    get<T>(key: string): T | undefined;
    update(key: string, value: unknown): PromiseLike<void> | void;
}

export const MUTE_KEY = 'loom.review.runNotice.muted';
const SEEN_KEY = 'loom.review.runNotice.seen';

/**
 * Open findings never stop a run; this only decides whether to mention them. A run starts at once, whatever the review says, and
 * afterwards a quiet message offers *Review* and *Don't show again* (never a question, never a condition for running). It is shown
 * once for an unchanged script: the same text with the same findings is not mentioned again, and a person who muted it is not asked.
 */
export class RunNotice {
    constructor(private readonly memory: Memory) {}

    /** Whether to mention the findings now. Calling it records that they were mentioned. */
    shouldOffer(file: string, text: string, openFindings: number): boolean {
        if (openFindings <= 0 || this.memory.get<boolean>(MUTE_KEY) === true) {
            return false;
        }
        const seen = this.memory.get<Record<string, string>>(SEEN_KEY) ?? {};
        const digest = createHash('sha256').update(text).update('\0').update(String(openFindings)).digest('hex');
        if (seen[file] === digest) {
            return false;
        }
        void this.memory.update(SEEN_KEY, { ...seen, [file]: digest });
        return true;
    }

    /** "Don't show again" for this workspace. */
    mute(): void {
        void this.memory.update(MUTE_KEY, true);
    }

    get muted(): boolean {
        return this.memory.get<boolean>(MUTE_KEY) === true;
    }
}
