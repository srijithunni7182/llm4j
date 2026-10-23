import { EngineClient, EngineState, RequestOptions } from './client';
import { EngineCancelledError, EngineStaleError } from './errors';
import { MethodName, Methods } from './protocol';

type Responder<M extends MethodName> = (params: Methods[M]['params']) => Methods[M]['answer'] | Promise<Methods[M]['answer']>;

/**
 * An engine for tests of everything that uses one: answers come from functions a test supplies (usually canned JSON the real engine
 * produced), it records every request, and a test can make the next request fail.
 */
export class FakeEngine implements EngineClient {
    state: EngineState = 'ready';
    readonly calls: Array<{ method: MethodName; params: unknown; key?: string }> = [];
    private readonly responders = new Map<MethodName, Responder<MethodName>>();
    private readonly failures: Error[] = [];
    private readonly listeners = new Set<(s: EngineState) => void>();
    private readonly latestKeys = new Map<string, AbortController>();
    private versions = 0;

    on<M extends MethodName>(method: M, responder: Responder<M>): this {
        this.responders.set(method, responder as unknown as Responder<MethodName>);
        return this;
    }

    /** The next request fails with this error. */
    failNext(error: Error): this {
        this.failures.push(error);
        return this;
    }

    setState(state: EngineState): void {
        this.state = state;
        this.listeners.forEach((l) => l(state));
    }

    onDidChangeState(listener: (state: EngineState) => void): { dispose(): void } {
        this.listeners.add(listener);
        return { dispose: () => this.listeners.delete(listener) };
    }

    async request<M extends MethodName>(method: M, params: Methods[M]['params'], options: RequestOptions = {}): Promise<Methods[M]['answer']> {
        return this.answer(method, params, undefined, options);
    }

    latest<M extends MethodName>(key: string, method: M, params: Methods[M]['params'], options: RequestOptions = {}): Promise<Methods[M]['answer']> {
        this.latestKeys.get(key)?.abort();
        const abort = new AbortController();
        this.latestKeys.set(key, abort);
        const version = ++this.versions;
        return this.answer(method, { ...params, version }, key, { ...options, signal: abort.signal }).then((answer) => {
            if (this.latestKeys.get(key) !== abort) {
                throw new EngineStaleError();
            }
            return answer;
        });
    }

    private async answer<M extends MethodName>(method: M, params: Methods[M]['params'], key: string | undefined, options: RequestOptions): Promise<Methods[M]['answer']> {
        this.calls.push({ method, params, key });
        const failure = this.failures.shift();
        if (failure) {
            throw failure;
        }
        const responder = this.responders.get(method);
        if (!responder) {
            throw new Error(`the fake engine has no answer for ${method}`);
        }
        const value = await responder(params);
        if (options.signal?.aborted) {
            throw new EngineCancelledError();
        }
        return value as Methods[M]['answer'];
    }

    dispose(): void {
        this.state = 'stopped';
    }
}
