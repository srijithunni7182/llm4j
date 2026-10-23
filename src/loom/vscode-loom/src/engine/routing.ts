import { CheckRequest, CheckRunner } from '../check/runner';
import { GraphAbortedError } from '../graph/errors';
import { GraphRequest, GraphRunner } from '../graph/runner';
import { EngineClient } from './client';
import { EngineCancelledError, EngineDiedError, EngineRpcError, EngineStaleError, EngineTimeoutError, EngineUnavailableError } from './errors';

/** Where the engine may be asked, and the unsaved text it should use for a file. */
export interface EngineSource {
    /** The engine, or undefined when there is none (no Java, never started). */
    engine(): EngineClient | undefined;
    /** The text of an open buffer with unsaved changes, else undefined (the engine reads the file). */
    unsavedText(file: string): string | undefined;
}

/** Whether an error means "ask the command instead", as opposed to a verdict about the request. */
function shouldFallBack(e: unknown): boolean {
    return (
        e instanceof EngineUnavailableError ||
        e instanceof EngineDiedError ||
        e instanceof EngineTimeoutError ||
        (e instanceof EngineRpcError && (e.kind === 'outside-workspace' || e.kind === 'unknown-method' || e.kind === 'internal'))
    );
}

/** A newer request replaced this one: the controller treats it as stopped, not failed. */
function asStopped(e: unknown): unknown {
    return e instanceof EngineCancelledError || e instanceof EngineStaleError ? new GraphAbortedError() : e;
}

/**
 * Problems from the engine when it is there, from `weave check` run as a process when it is not (or when it fails in a way the
 * process might not). The answer is the same JSON either way, so the controller does not know which it got.
 */
export class EngineCheckRunner implements CheckRunner {
    constructor(private readonly source: EngineSource, private readonly fallback: CheckRunner) {}

    async run(request: CheckRequest): Promise<string> {
        const engine = this.source.engine();
        if (!engine) {
            return this.fallback.run(request);
        }
        try {
            const answer = await engine.latest(`check:${request.file}`, 'loom/check', { file: request.file, text: this.source.unsavedText(request.file) }, { signal: request.signal });
            return JSON.stringify(answer.result);
        } catch (e) {
            if (shouldFallBack(e)) {
                return this.fallback.run(request);
            }
            throw asStopped(e);
        }
    }
}

/** The graph from the engine for JSON, from the process for anything else (Mermaid) or when the engine cannot answer. */
export class EngineGraphRunner implements GraphRunner {
    constructor(private readonly source: EngineSource, private readonly fallback: GraphRunner) {}

    async run(request: GraphRequest): Promise<string> {
        const engine = this.source.engine();
        if (!engine || request.format !== 'json') {
            return this.fallback.run(request);
        }
        try {
            const answer = await engine.latest(
                `graph:${request.file}`,
                'loom/graph',
                { file: request.file, workflow: request.workflow },
                { signal: request.signal },
            );
            return JSON.stringify(answer.result);
        } catch (e) {
            if (shouldFallBack(e)) {
                return this.fallback.run(request);
            }
            // the command said it could not build a graph: let the process say it the way it always has
            if (e instanceof EngineRpcError && e.kind === 'engine-failure') {
                return this.fallback.run(request);
            }
            throw asStopped(e);
        }
    }
}
