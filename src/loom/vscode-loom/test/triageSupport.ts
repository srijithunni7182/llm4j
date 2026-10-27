import { EditHost, FileChange } from '../src/review/triage';
import { FindingData } from '../src/review/model';
import { Memory } from '../src/review/runNotice';

/** An editor that records what it was asked to do. */
export class FakeEditHost implements EditHost {
    readonly applied: FileChange[][] = [];
    readonly confirmed: Array<{ title: string; change: FileChange }> = [];
    readonly undone: string[][] = [];
    files = new Map<string, string>();
    answerConfirm = true;
    refuseApply = false;

    async text(file: string): Promise<string | undefined> {
        return this.files.get(file);
    }
    async apply(changes: FileChange[]): Promise<boolean> {
        if (this.refuseApply) {
            return false;
        }
        this.applied.push(changes);
        changes.forEach((c) => this.files.set(c.file, c.after));
        return true;
    }
    async confirm(title: string, change: FileChange): Promise<boolean> {
        this.confirmed.push({ title, change });
        return this.answerConfirm;
    }
    async undo(files: string[]): Promise<void> {
        this.undone.push(files);
    }
}

export function memory(): Memory & { store: Map<string, unknown> } {
    const store = new Map<string, unknown>();
    return { store, get: <T>(k: string) => store.get(k) as T | undefined, update: async (k: string, v: unknown) => { store.set(k, v); } };
}

export function finding(rule: string, extra: Partial<FindingData> & { options?: unknown[] } = {}): FindingData {
    return {
        rule,
        fingerprint: extra.fingerprint ?? `fp-${rule}`,
        lens: 'reliability',
        severity: 'medium',
        title: `${rule} title`,
        why: `${rule} why`,
        fixHint: '',
        location: { file: 'main.loom', line: 3, column: 1, endLine: 3, endColumn: 10, node: null },
        ...extra,
    } as FindingData;
}

export const SAFE_FIX = { safety: 'safe' as const, label: 'Add approve', edits: [] };
export const REVIEW_FIX = { safety: 'review' as const, label: 'Add retry and timeout', edits: [] };
