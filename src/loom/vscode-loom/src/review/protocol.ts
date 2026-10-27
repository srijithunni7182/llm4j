/** What the extension tells the review panel, and what the panel may tell the extension. */

export type ReviewStatus = 'loading' | 'running' | 'idle' | 'stale' | 'error';

export type ReviewHostMessage =
    | { type: 'review'; review: unknown; graph: unknown; status: ReviewStatus; message?: string; entry?: string; dismissed?: unknown[] }
    | { type: 'toast'; text: string; ok: boolean; token?: number }
    | { type: 'status'; status: ReviewStatus; message?: string }
    | { type: 'settings'; layout: 'split' | 'outline' | 'list' }
    | { type: 'cursor'; file: string; line: number };

export type ReviewWebviewMessage =
    | { type: 'ready' }
    | { type: 'reveal'; file: string; line: number; beside: boolean }
    | { type: 'rerun' }
    | { type: 'selectWorkflow'; name: string }
    | { type: 'fix'; ids: string[] }
    | { type: 'fixOption'; id: string; option: string; answers: Record<string, string> }
    | { type: 'accept'; ids: string[]; scope: 'finding' | 'thing' | 'script' | 'project'; reason: string; until?: string; on?: string; confirm?: number }
    | { type: 'dismiss'; ids: string[] }
    | { type: 'restore'; ids: string[] }
    | { type: 'restoreAll' }
    | { type: 'explain'; rule: string }
    | { type: 'baseline'; includeSerious: boolean }
    | { type: 'undo'; token: number };

const MAX_LINE = 10_000_000;
const MAX_TEXT = 4096;

function isObject(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function text(value: unknown): value is string {
    return typeof value === 'string' && value.length > 0 && value.length <= MAX_TEXT;
}

/** Accepts a message from the panel only when it has exactly the shape of one of {@link ReviewWebviewMessage}. */
export function parseReviewMessage(raw: unknown): ReviewWebviewMessage | undefined {
    if (!isObject(raw)) {
        return undefined;
    }
    switch (raw.type) {
        case 'ready':
            return { type: 'ready' };
        case 'rerun':
            return { type: 'rerun' };
        case 'reveal':
            if (text(raw.file) && Number.isInteger(raw.line) && (raw.line as number) >= 1 && (raw.line as number) <= MAX_LINE) {
                return { type: 'reveal', file: raw.file, line: raw.line as number, beside: raw.beside === true };
            }
            return undefined;
        case 'selectWorkflow':
            return text(raw.name) ? { type: 'selectWorkflow', name: raw.name } : undefined;
        case 'fix':
            return ids(raw.ids) ? { type: 'fix', ids: raw.ids } : undefined;
        case 'dismiss':
            return ids(raw.ids) ? { type: 'dismiss', ids: raw.ids } : undefined;
        case 'restore':
            return ids(raw.ids) ? { type: 'restore', ids: raw.ids } : undefined;
        case 'restoreAll':
            return { type: 'restoreAll' };
        case 'explain':
            return rule(raw.rule) ? { type: 'explain', rule: raw.rule } : undefined;
        case 'baseline':
            return { type: 'baseline', includeSerious: raw.includeSerious === true };
        case 'undo':
            return Number.isInteger(raw.token) && (raw.token as number) >= 0 ? { type: 'undo', token: raw.token as number } : undefined;
        case 'fixOption': {
            const answers = strings(raw.answers);
            return text(raw.id) && text(raw.option) && answers ? { type: 'fixOption', id: raw.id, option: raw.option, answers } : undefined;
        }
        case 'accept': {
            const scopes = ['finding', 'thing', 'script', 'project'];
            if (!ids(raw.ids) || typeof raw.scope !== 'string' || !scopes.includes(raw.scope) || typeof raw.reason !== 'string' || raw.reason.trim() === '' || raw.reason.length > MAX_TEXT) {
                return undefined;
            }
            const accept: ReviewWebviewMessage = { type: 'accept', ids: raw.ids, scope: raw.scope as 'finding' | 'thing' | 'script' | 'project', reason: raw.reason.trim() };
            if (typeof raw.until === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(raw.until)) {
                accept.until = raw.until;
            } else if (raw.until !== undefined && raw.until !== '') {
                return undefined;
            }
            if (typeof raw.on === 'string' && raw.on.length <= 200) {
                accept.on = raw.on;
            }
            if (Number.isInteger(raw.confirm) && (raw.confirm as number) > 0) {
                accept.confirm = raw.confirm as number;
            }
            return accept;
        }
        default:
            return undefined;
    }
}

const MAX_IDS = 2000;

function ids(value: unknown): value is string[] {
    return Array.isArray(value) && value.length > 0 && value.length <= MAX_IDS && value.every((v) => typeof v === 'string' && v.length > 0 && v.length <= 128);
}

function rule(value: unknown): value is string {
    return typeof value === 'string' && /^[A-Za-z0-9_-]{1,32}$/.test(value);
}

function strings(value: unknown): Record<string, string> | undefined {
    if (!isObject(value)) {
        return {};
    }
    const out: Record<string, string> = {};
    for (const [k, v] of Object.entries(value)) {
        if (typeof v !== 'string' || v.length > MAX_TEXT || k.length > 64) {
            return undefined;
        }
        out[k] = v;
    }
    return out;
}
