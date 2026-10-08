import { ChildProcess, spawn as nodeSpawn } from 'child_process';
import * as fs from 'fs';
import { GraphAbortedError, GraphExitError, GraphTimeoutError, OutputTooLargeError, RuntimeMissingError } from './errors';
import { MAX_GRAPH_BYTES } from './model';

export interface RunnerOptions {
    /** The `java` executable (setting loom.graph.javaPath). */
    javaPath: string;
    /** The bundled weave.jar. */
    jarPath: string;
    timeoutMs: number;
}

export interface GraphRequest {
    file: string;
    format: 'json' | 'mermaid';
    workflow?: string;
    signal: AbortSignal;
}

/** What the extension needs from a graph source; tests supply fakes. */
export interface GraphRunner {
    run(request: GraphRequest): Promise<string>;
}

export type SpawnFn = (command: string, args: string[]) => ChildProcess;

/**
 * Runs `java -jar weave.jar graph <file>` and returns what it printed. The file is one element of the argument
 * array, never part of a shell string. The process is killed when the request is aborted or times out.
 */
export class WeaveGraphRunner implements GraphRunner {
    constructor(
        private readonly options: RunnerOptions,
        private readonly spawnFn: SpawnFn = (command, args) => nodeSpawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] }),
        private readonly jarExists: (path: string) => boolean = fs.existsSync,
    ) {}

    /** Checks that weave.jar is there and java starts, so a missing runtime is reported before anything opens. */
    async verifyRuntime(): Promise<void> {
        if (!this.jarExists(this.options.jarPath)) {
            throw new RuntimeMissingError('jar', `weave.jar was not found at ${this.options.jarPath}. Reinstall the Loom extension.`);
        }
        await new Promise<void>((resolve, reject) => {
            let child: ChildProcess;
            try {
                child = this.spawnFn(this.options.javaPath, ['-version']);
            } catch (e) {
                reject(this.javaMissing(e as Error));
                return;
            }
            child.once('error', (e) => reject(this.javaMissing(e)));
            child.once('exit', (code) => (code === 0 ? resolve() : reject(this.javaMissing(new Error(`java -version exited with code ${code}`)))));
            child.stdout?.resume();
            child.stderr?.resume();
        });
    }

    run(request: GraphRequest): Promise<string> {
        const args = ['graph', request.file, '--format', request.format];
        if (request.workflow !== undefined) {
            args.push('--workflow', request.workflow);
        }
        return this.runWeave(args, request.signal, [0]);
    }

    /**
     * Runs `java -jar weave.jar <args>` and returns what it printed. Every argument is one element of the array, never part of a
     * shell string. An exit code not in `acceptedExits` is a {@link GraphExitError}; the process is killed when `signal` aborts or
     * the timeout passes.
     */
    runWeave(weaveArgs: string[], signal: AbortSignal, acceptedExits: number[]): Promise<string> {
        const args = ['-jar', this.options.jarPath, ...weaveArgs];
        const request = { signal };
        return new Promise<string>((resolve, reject) => {
            if (request.signal.aborted) {
                reject(new GraphAbortedError());
                return;
            }
            let child: ChildProcess;
            try {
                child = this.spawnFn(this.options.javaPath, args);
            } catch (e) {
                reject(this.javaMissing(e as Error));
                return;
            }
            const out: Buffer[] = [];
            let size = 0;
            let stderr = '';
            let settled = false;
            const finish = (action: () => void): void => {
                if (settled) {
                    return;
                }
                settled = true;
                clearTimeout(timer);
                request.signal.removeEventListener('abort', onAbort);
                action();
            };
            const stop = (error: Error): void => {
                finish(() => {
                    child.kill();
                    reject(error);
                });
            };
            const onAbort = (): void => stop(new GraphAbortedError());
            const timer = setTimeout(() => stop(new GraphTimeoutError(this.options.timeoutMs)), this.options.timeoutMs);
            request.signal.addEventListener('abort', onAbort);

            child.stdout?.on('data', (chunk: Buffer) => {
                size += chunk.length;
                if (size > MAX_GRAPH_BYTES) {
                    stop(new OutputTooLargeError(MAX_GRAPH_BYTES));
                    return;
                }
                out.push(chunk);
            });
            child.stderr?.on('data', (chunk: Buffer) => {
                if (stderr.length < 16_384) {
                    stderr += chunk.toString('utf8');
                }
            });
            child.once('error', (e) => finish(() => reject(this.javaMissing(e))));
            child.once('exit', (code) =>
                finish(() => (code !== null && acceptedExits.includes(code) ? resolve(Buffer.concat(out).toString('utf8')) : reject(new GraphExitError(code, stderr)))),
            );
        });
    }

    private javaMissing(cause: Error): RuntimeMissingError {
        return new RuntimeMissingError(
            'java',
            `Java was not found (${this.options.javaPath}). Install Java 17 or newer, or set loom.graph.javaPath. ${cause.message}`,
        );
    }
}
