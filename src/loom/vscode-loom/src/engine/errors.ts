import { ServerErrorKind } from './protocol';

/** The server answered with an error. */
export class EngineRpcError extends Error {
    constructor(readonly code: number, readonly kind: ServerErrorKind | string, message: string) {
        super(message);
        this.name = 'EngineRpcError';
    }
}

/** The request was withdrawn (a newer one for the same file arrived, or the caller aborted). */
export class EngineCancelledError extends Error {
    constructor(message = 'the request was cancelled') {
        super(message);
        this.name = 'EngineCancelledError';
    }
}

/** The answer was for a document older than the latest one asked about and was dropped. */
export class EngineStaleError extends Error {
    constructor() {
        super('the answer was for an older version of the document');
        this.name = 'EngineStaleError';
    }
}

export class EngineTimeoutError extends Error {
    constructor(readonly timeoutMs: number) {
        super(`the engine did not answer within ${timeoutMs} ms`);
        this.name = 'EngineTimeoutError';
    }
}

/** The engine process ended while the request was waiting. */
export class EngineDiedError extends Error {
    constructor() {
        super('the engine process ended before it answered');
        this.name = 'EngineDiedError';
    }
}

/** There is no engine to ask: it never started, keeps crashing, or speaks another protocol. */
export class EngineUnavailableError extends Error {
    constructor(readonly state: string, message: string) {
        super(message);
        this.name = 'EngineUnavailableError';
    }
}
