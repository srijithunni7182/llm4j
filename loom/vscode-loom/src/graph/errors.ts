/** Why `weave graph` could not give a graph. Each has a message a person can act on. */

export class RuntimeMissingError extends Error {
    constructor(readonly what: 'java' | 'jar', message: string) {
        super(message);
        this.name = 'RuntimeMissingError';
    }
}

export class GraphTimeoutError extends Error {
    constructor(readonly timeoutMs: number) {
        super(`weave graph took longer than ${Math.round(timeoutMs / 1000)} s and was stopped. Raise loom.graph.timeoutMs if the script is large.`);
        this.name = 'GraphTimeoutError';
    }
}

export class GraphAbortedError extends Error {
    constructor() {
        super('weave graph was stopped');
        this.name = 'GraphAbortedError';
    }
}

/** weave graph exited with a non-zero code; `stderr` holds what it said. Exit code 2 means no graph could be built. */
export class GraphExitError extends Error {
    constructor(readonly code: number | null, readonly stderr: string) {
        super(stderr.trim() || `weave graph exited with code ${code}`);
        this.name = 'GraphExitError';
    }

    /** The `file:line` weave graph reports, when its message has one. */
    location(): { file: string; line: number } | undefined {
        const m = /(?:^|\s)((?:[A-Za-z]:)?[^\s:]+\.loom):(\d+)/.exec(this.message);
        return m ? { file: m[1], line: Number(m[2]) } : undefined;
    }
}

export class OutputTooLargeError extends Error {
    constructor(readonly limit: number) {
        super(`weave graph printed more than ${Math.round(limit / (1024 * 1024))} MB and was stopped`);
        this.name = 'OutputTooLargeError';
    }
}
