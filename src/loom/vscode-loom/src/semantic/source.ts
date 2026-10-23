import { EngineClient } from '../engine/client';
import { parseIndex, SymbolIndex } from './symbols';

/** What the providers need: the index of a script and its imports, or nothing when the engine cannot give one. */
export interface SymbolsSource {
    index(file: string, signal?: AbortSignal): Promise<SymbolIndex | undefined>;
}

/** The unsaved state of the editor, for a request: this file's text, and the other changed files'. */
export interface Unsaved {
    text?: string;
    buffers?: Record<string, string>;
}

/**
 * The index from the engine's `loom/symbols`. Any failure (no engine, a timeout, a withdrawn request, an answer in a form this
 * extension does not know) is "no index": the providers then step aside and the editor's older features answer.
 */
export class EngineSymbolsSource implements SymbolsSource {
    constructor(
        private readonly engine: () => EngineClient | undefined,
        private readonly unsaved: (file: string) => Unsaved,
        private readonly log?: (line: string) => void,
    ) {}

    async index(file: string, signal?: AbortSignal): Promise<SymbolIndex | undefined> {
        const engine = this.engine();
        if (!engine) {
            return undefined;
        }
        try {
            const u = this.unsaved(file);
            const answer = await engine.request('loom/symbols', { file, text: u.text, buffers: u.buffers }, { signal, timeoutMs: 5000 });
            const index = parseIndex(answer.result);
            if (!index) {
                this.log?.('the engine answered loom/symbols in a form this extension does not know');
            }
            return index;
        } catch (e) {
            this.log?.(`loom/symbols failed: ${e instanceof Error ? e.message : String(e)}`);
            return undefined;
        }
    }
}
