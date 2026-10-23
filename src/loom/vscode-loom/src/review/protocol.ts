/** What the extension tells the review panel, and what the panel may tell the extension. */

export type ReviewStatus = 'loading' | 'running' | 'idle' | 'stale' | 'error';

export type ReviewHostMessage =
    | { type: 'review'; review: unknown; graph: unknown; status: ReviewStatus; message?: string; entry?: string }
    | { type: 'status'; status: ReviewStatus; message?: string }
    | { type: 'cursor'; file: string; line: number };

export type ReviewWebviewMessage =
    | { type: 'ready' }
    | { type: 'reveal'; file: string; line: number; beside: boolean }
    | { type: 'rerun' }
    | { type: 'selectWorkflow'; name: string };

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
        default:
            return undefined;
    }
}
