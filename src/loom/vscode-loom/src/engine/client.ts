import { Scheduler, TimerHandle } from '../graph/controller';
import {
    EngineCancelledError,
    EngineDiedError,
    EngineRpcError,
    EngineStaleError,
    EngineTimeoutError,
    EngineUnavailableError,
} from './errors';
import { encodeFrame, FrameDecoder, FrameError } from './framing';
import { InitializeResult, MethodName, Methods, PROTOCOL_VERSION } from './protocol';

/** The engine as the rest of the extension sees it: tests use a fake, the extension a process. */
export interface EngineClient {
    readonly state: EngineState;
    /** Calls `listener` whenever the state changes. */
    onDidChangeState(listener: (state: EngineState) => void): { dispose(): void };
    request<M extends MethodName>(method: M, params: Methods[M]['params'], options?: RequestOptions): Promise<Methods[M]['answer']>;
    /**
     * As {@link request}, for the latest answer about `key` (usually a file): asking again for the same key withdraws the earlier
     * request, and an answer for an older document version is dropped as stale.
     */
    latest<M extends MethodName>(key: string, method: M, params: Methods[M]['params'], options?: RequestOptions): Promise<Methods[M]['answer']>;
    dispose(): void;
}

/**
 * starting: the process is up and has not answered yet; ready: it has; restarting: it died and a new one is coming; unavailable: it
 * could not be started or keeps dying; mismatch: it speaks another protocol; stopped: disposed.
 */
export type EngineState = 'starting' | 'ready' | 'restarting' | 'unavailable' | 'mismatch' | 'stopped';

export interface RequestOptions {
    signal?: AbortSignal;
    timeoutMs?: number;
}

/** A running `weave serve`; the real one wraps a child process, tests supply a fake. */
export interface EngineProcess {
    write(bytes: Buffer): void;
    onData(listener: (chunk: Buffer) => void): void;
    onStderr(listener: (text: string) => void): void;
    onExit(listener: (code: number | null) => void): void;
    kill(): void;
}

export interface ProcessEngineOptions {
    /** Starts a process; may throw (Java missing). */
    start: () => EngineProcess;
    /** The folders the editor has open, sent with `initialize`. */
    roots: () => string[];
    scheduler: Scheduler;
    /** Waits before restart 1, 2, 3, …; the last is used for any further one. */
    backoffMs?: number[];
    /** Consecutive failures before the engine is given up as unavailable. */
    maxFailures?: number;
    /** A little longer than the server's own limit, so the server's answer arrives first. */
    requestTimeoutMs?: number;
    log?: (line: string) => void;
}

export const DEFAULT_BACKOFF_MS = [250, 1000, 4000];
export const DEFAULT_MAX_FAILURES = 5;
export const DEFAULT_REQUEST_TIMEOUT_MS = 12_000;

interface Pending {
    resolve(value: unknown): void;
    reject(error: Error): void;
    timer: TimerHandle;
    detach(): void;
}

/**
 * Talks to one `weave serve` process: starts it, does the handshake, sends requests and matches answers, withdraws requests, and
 * starts it again when it dies (with growing waits, giving up after repeated failures). It is the only place that knows about the
 * process, the framing and the protocol version.
 */
export class ProcessEngineClient implements EngineClient {
    private currentState: EngineState = 'stopped';
    private process: EngineProcess | undefined;
    private nextId = 1;
    private readonly pending = new Map<number, Pending>();
    private readonly waiting: Array<{ go: () => void; fail: (e: Error) => void }> = [];
    private readonly listeners = new Set<(state: EngineState) => void>();
    private readonly latestByKey = new Map<string, { abort: AbortController; version: number }>();
    private versionCounter = 0;
    private failures = 0;
    private restartTimer: TimerHandle | undefined;
    private disposed = false;
    private generation = 0;
    private readonly stderrTail: string[] = [];
    private why = '';

    constructor(private readonly options: ProcessEngineOptions) {}

    get state(): EngineState {
        return this.currentState;
    }

    /** Why the engine is unavailable or mismatched, for the one place that says so (the status bar). */
    get reason(): string {
        return this.why;
    }

    /** The last lines the engine wrote to standard error, for "show the engine log". */
    log(): string[] {
        return [...this.stderrTail];
    }

    onDidChangeState(listener: (state: EngineState) => void): { dispose(): void } {
        this.listeners.add(listener);
        return { dispose: () => this.listeners.delete(listener) };
    }

    /** Starts the engine. Safe to call again after `unavailable` to try once more. */
    start(): void {
        if (this.disposed) {
            return;
        }
        this.failures = 0;
        this.launch();
    }

    request<M extends MethodName>(method: M, params: Methods[M]['params'], options: RequestOptions = {}): Promise<Methods[M]['answer']> {
        return this.send(method, params, options) as Promise<Methods[M]['answer']>;
    }

    latest<M extends MethodName>(key: string, method: M, params: Methods[M]['params'], options: RequestOptions = {}): Promise<Methods[M]['answer']> {
        this.latestByKey.get(key)?.abort.abort();
        const abort = new AbortController();
        const version = ++this.versionCounter;
        this.latestByKey.set(key, { abort, version });
        if (options.signal) {
            options.signal.addEventListener('abort', () => abort.abort(), { once: true });
        }
        const withVersion = { ...params, version } as Methods[M]['params'];
        return this.send(method, withVersion, { ...options, signal: abort.signal }).then((answer) => {
            const current = this.latestByKey.get(key);
            const said = (answer as { requestVersion?: number }).requestVersion;
            if (current && current.version !== version) {
                throw new EngineStaleError();
            }
            if (said !== undefined && said !== version) {
                throw new EngineStaleError();
            }
            if (current && current.version === version) {
                this.latestByKey.delete(key);
            }
            return answer as Methods[M]['answer'];
        }) as Promise<Methods[M]['answer']>;
    }

    dispose(): void {
        if (this.disposed) {
            return;
        }
        this.disposed = true;
        this.restartTimer?.cancel();
        this.failAll(new EngineUnavailableError('stopped', 'the engine was stopped'));
        const p = this.process;
        this.process = undefined;
        if (p) {
            try {
                p.write(encodeFrame({ jsonrpc: '2.0', method: 'loom/shutdown', params: {} }));
            } catch {
                // the process is gone already
            }
            p.kill();
        }
        this.setState('stopped');
    }

    // ---- starting and restarting

    private launch(): void {
        if (this.disposed) {
            return;
        }
        const generation = ++this.generation;
        this.setState(this.failures > 0 ? 'restarting' : 'starting');
        let process: EngineProcess;
        try {
            process = this.options.start();
        } catch (e) {
            this.options.log?.(`the engine could not be started: ${(e as Error).message}`);
            this.failures = this.maxFailures();
            this.giveUp(`the engine could not be started: ${(e as Error).message}`);
            return;
        }
        this.process = process;
        const decoder = new FrameDecoder();
        process.onData((chunk) => {
            if (generation !== this.generation) {
                return;
            }
            try {
                decoder.push(chunk).forEach((m) => this.received(m));
            } catch (e) {
                if (e instanceof FrameError) {
                    this.options.log?.(`the engine's output could not be read: ${e.message}`);
                    process.kill();
                    return;
                }
                throw e;
            }
        });
        process.onStderr((text) => this.remember(text));
        process.onExit((code) => {
            if (generation === this.generation) {
                this.exited(code);
            }
        });
        const handshake = this.rawRequest('initialize', { roots: this.options.roots() });
        handshake.then(
            (result) => {
                if (generation !== this.generation) {
                    return;
                }
                const init = result as InitializeResult;
                if (init.protocol !== PROTOCOL_VERSION) {
                    this.options.log?.(`the engine speaks protocol ${init.protocol}, this extension speaks ${PROTOCOL_VERSION}`);
                    this.why = `the engine speaks protocol ${init.protocol} and this extension speaks ${PROTOCOL_VERSION}; reinstall the extension`;
                    this.setState('mismatch');
                    this.failAll(new EngineUnavailableError('mismatch', this.why));
                    process.kill();
                    return;
                }
                this.setState('ready');
                this.waiting.splice(0).forEach((w) => w.go());
            },
            () => {
                // the process died or was killed; exited() handles it
            },
        );
    }

    private exited(code: number | null): void {
        this.process = undefined;
        if (this.disposed || this.currentState === 'mismatch') {
            return;
        }
        this.options.log?.(`the engine ended (exit ${code})`);
        this.failInFlight(new EngineDiedError()); // requests still waiting for a start go to the next one
        this.failures += 1;
        if (this.failures > this.maxFailures()) {
            this.giveUp('the engine keeps stopping');
            return;
        }
        this.setState('restarting');
        const backoff = this.options.backoffMs ?? DEFAULT_BACKOFF_MS;
        const wait = backoff[Math.min(this.failures - 1, backoff.length - 1)];
        this.restartTimer = this.options.scheduler.after(wait, () => this.launch());
    }

    private giveUp(why: string): void {
        this.why = why;
        this.setState('unavailable');
        this.failAll(new EngineUnavailableError('unavailable', why));
    }

    private maxFailures(): number {
        return this.options.maxFailures ?? DEFAULT_MAX_FAILURES;
    }

    private remember(text: string): void {
        for (const line of text.split(/\r?\n/)) {
            if (line.trim()) {
                this.stderrTail.push(line);
                if (this.stderrTail.length > 200) {
                    this.stderrTail.shift();
                }
            }
        }
    }

    // ---- requests

    private send(method: string, params: unknown, options: RequestOptions): Promise<unknown> {
        if (options.signal?.aborted) {
            return Promise.reject(new EngineCancelledError());
        }
        switch (this.currentState) {
            case 'ready':
                return this.rawRequest(method, params, options);
            case 'starting':
            case 'restarting':
                return new Promise((resolve, reject) => {
                    const entry = {
                        go: () => this.rawRequest(method, params, options).then(resolve, reject),
                        fail: reject,
                    };
                    this.waiting.push(entry);
                    options.signal?.addEventListener(
                        'abort',
                        () => {
                            const i = this.waiting.indexOf(entry);
                            if (i >= 0) {
                                this.waiting.splice(i, 1);
                                reject(new EngineCancelledError());
                            }
                        },
                        { once: true },
                    );
                });
            default:
                return Promise.reject(new EngineUnavailableError(this.currentState, this.why || `the engine is ${this.currentState}`));
        }
    }

    private rawRequest(method: string, params: unknown, options: RequestOptions = {}): Promise<unknown> {
        return new Promise((resolve, reject) => {
            const process = this.process;
            if (!process) {
                reject(new EngineUnavailableError(this.currentState, `the engine is ${this.currentState}`));
                return;
            }
            const id = this.nextId++;
            const timeoutMs = options.timeoutMs ?? this.options.requestTimeoutMs ?? DEFAULT_REQUEST_TIMEOUT_MS;
            const onAbort = (): void => {
                if (this.pending.delete(id)) {
                    this.cancelOnServer(id);
                    entry.timer.cancel();
                    reject(new EngineCancelledError());
                }
            };
            const entry: Pending = {
                resolve,
                reject,
                timer: this.options.scheduler.after(timeoutMs, () => {
                    if (this.pending.delete(id)) {
                        this.cancelOnServer(id);
                        reject(new EngineTimeoutError(timeoutMs));
                    }
                }),
                detach: () => options.signal?.removeEventListener('abort', onAbort),
            };
            this.pending.set(id, entry);
            options.signal?.addEventListener('abort', onAbort, { once: true });
            try {
                process.write(encodeFrame({ jsonrpc: '2.0', id, method, params }));
            } catch (e) {
                this.pending.delete(id);
                entry.timer.cancel();
                entry.detach();
                reject(new EngineDiedError());
            }
        });
    }

    private cancelOnServer(id: number): void {
        try {
            this.process?.write(encodeFrame({ jsonrpc: '2.0', method: 'loom/cancel', params: { id } }));
        } catch {
            // the process is gone; nothing is left to cancel
        }
    }

    private received(frame: string): void {
        let message: { id?: number | null; result?: unknown; error?: { code: number; message: string; data?: { kind?: string } } };
        try {
            message = JSON.parse(frame);
        } catch {
            this.options.log?.(`the engine sent something that is not JSON: ${frame.slice(0, 80)}`);
            return;
        }
        if (typeof message.id !== 'number') {
            return;
        }
        const entry = this.pending.get(message.id);
        if (!entry) {
            return; // withdrawn or timed out
        }
        this.pending.delete(message.id);
        entry.timer.cancel();
        entry.detach();
        if (message.error) {
            entry.reject(new EngineRpcError(message.error.code, message.error.data?.kind ?? 'unknown', message.error.message));
            return;
        }
        // an answer to a real request means the engine is healthy: forget earlier failures
        if (this.currentState === 'ready') {
            this.failures = 0;
        }
        entry.resolve(message.result);
    }

    private failInFlight(error: Error): void {
        for (const [, entry] of this.pending) {
            entry.timer.cancel();
            entry.detach();
            entry.reject(error);
        }
        this.pending.clear();
    }

    private failAll(error: Error): void {
        this.failInFlight(error);
        this.waiting.splice(0).forEach((w) => w.fail(error));
    }

    private setState(state: EngineState): void {
        if (this.currentState === state) {
            return;
        }
        this.currentState = state;
        this.listeners.forEach((l) => l(state));
    }
}
