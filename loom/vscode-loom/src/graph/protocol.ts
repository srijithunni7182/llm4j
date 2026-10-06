import { GraphResult } from './model';

/** What the extension tells the panel. */
export type HostMessage =
    | { type: 'loading'; file: string }
    | { type: 'graph'; result: GraphResult; workflow: string }
    | { type: 'stale'; message: string; file?: string; line?: number }
    | { type: 'highlight'; id: string | null };

/** What the panel tells the extension. Everything the panel sends is untrusted until {@link parseWebviewMessage} accepts it. */
export type WebviewMessage =
    | { type: 'ready' }
    | { type: 'openSource'; file: string; line: number; beside: boolean }
    | { type: 'selectWorkflow'; name: string }
    | { type: 'copyMermaid'; name: string };

const MAX_LINE = 10_000_000;
const MAX_TEXT = 4096;

function isObject(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function text(value: unknown): value is string {
    return typeof value === 'string' && value.length > 0 && value.length <= MAX_TEXT;
}

/** Accepts a message from the panel only when it has exactly the shape of one of {@link WebviewMessage}. */
export function parseWebviewMessage(raw: unknown): WebviewMessage | undefined {
    if (!isObject(raw)) {
        return undefined;
    }
    switch (raw.type) {
        case 'ready':
            return { type: 'ready' };
        case 'openSource':
            if (text(raw.file) && Number.isInteger(raw.line) && (raw.line as number) >= 1 && (raw.line as number) <= MAX_LINE) {
                return { type: 'openSource', file: raw.file, line: raw.line as number, beside: raw.beside === true };
            }
            return undefined;
        case 'selectWorkflow':
            return text(raw.name) ? { type: 'selectWorkflow', name: raw.name } : undefined;
        case 'copyMermaid':
            return text(raw.name) ? { type: 'copyMermaid', name: raw.name } : undefined;
        default:
            return undefined;
    }
}
