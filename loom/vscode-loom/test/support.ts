import { EventEmitter } from 'events';
import { ChildProcess } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { PanelHost, Scheduler, TimerHandle } from '../src/graph/controller';
import { GraphResult } from '../src/graph/model';
import { HostMessage } from '../src/graph/protocol';
import { GraphRequest, GraphRunner } from '../src/graph/runner';

const GOLDEN = path.join(__dirname, '..', '..', '..', 'ai-agent4j-loom', 'src', 'test', 'resources', 'graph', 'golden');

/** The JSON `weave graph` printed for a sample script (the Loom module's golden files). */
export function golden(name: string): string {
    return fs.readFileSync(path.join(GOLDEN, name + '.json'), 'utf8');
}

export function goldenResult(name: string): GraphResult {
    return JSON.parse(golden(name)) as GraphResult;
}

/** A graph result built by hand: two files, two workflows, a few steps with lines. */
export function sampleResult(overrides: Partial<GraphResult> = {}): GraphResult {
    const main = '/p/main.loom';
    const lib = '/p/lib.loom';
    return {
        version: 1,
        entry: main,
        files: [{ path: main, imports: [lib] }, { path: lib, imports: [] }],
        workflows: [
            {
                name: 'First', file: main, line: 3, params: [],
                nodes: [
                    { id: 'start', kind: 'start', label: 'Start' },
                    { id: 'n1', kind: 'note', label: 'note', source: { file: main, line: 4 } },
                    { id: 'n2', kind: 'call', label: 'call Lib', source: { file: main, line: 5 }, call: { workflow: 'Lib', file: lib } },
                    { id: 'end', kind: 'end', label: 'End' },
                ],
                edges: [{ from: 'start', to: 'n1' }, { from: 'n1', to: 'n2' }, { from: 'n2', to: 'end' }],
            },
            {
                name: 'Second', file: main, line: 10, params: [],
                nodes: [
                    { id: 'start', kind: 'start', label: 'Start' },
                    { id: 'n1', kind: 'note', label: 'note', source: { file: main, line: 11 } },
                    { id: 'end', kind: 'end', label: 'End' },
                ],
                edges: [{ from: 'start', to: 'n1' }, { from: 'n1', to: 'end' }],
            },
            { name: 'Lib', file: lib, line: 1, params: [], nodes: [{ id: 'start', kind: 'start', label: 'Start' }, { id: 'end', kind: 'end', label: 'End' }], edges: [{ from: 'start', to: 'end' }] },
        ],
        agents: [],
        diagnostics: [],
        ...overrides,
    };
}

/** A child process that does what a test tells it to. */
export class FakeChild extends EventEmitter {
    stdout = new EventEmitter() as EventEmitter & { resume(): void };
    stderr = new EventEmitter() as EventEmitter & { resume(): void };
    killed = false;
    constructor() {
        super();
        this.stdout.resume = () => undefined;
        this.stderr.resume = () => undefined;
    }
    kill(): boolean {
        this.killed = true;
        return true;
    }
    asChild(): ChildProcess {
        return this as unknown as ChildProcess;
    }
}

/** Runs of weave graph that a test finishes by hand. */
export class FakeRunner implements GraphRunner {
    requests: GraphRequest[] = [];
    private waiting: Array<{ resolve: (text: string) => void; reject: (e: Error) => void; request: GraphRequest }> = [];

    run(request: GraphRequest): Promise<string> {
        this.requests.push(request);
        return new Promise((resolve, reject) => {
            this.waiting.push({ resolve, reject, request });
            request.signal.addEventListener('abort', () => reject(new Error('aborted')));
        });
    }

    get inFlight(): number {
        return this.waiting.length;
    }

    finish(text: string, index = this.waiting.length - 1): void {
        this.waiting[index].resolve(text);
    }

    fail(error: Error, index = this.waiting.length - 1): void {
        this.waiting[index].reject(error);
    }
}

export class FakeHost implements PanelHost {
    posted: HostMessage[] = [];
    opened: Array<{ file: string; line: number; beside: boolean }> = [];
    reports: Array<{ message: string; kind: string }> = [];
    copied: string[] = [];
    post(message: HostMessage): void {
        this.posted.push(message);
    }
    openSource(file: string, line: number, beside: boolean): void {
        this.opened.push({ file, line, beside });
    }
    report(message: string, kind: 'error' | 'warning' | 'info'): void {
        this.reports.push({ message, kind });
    }
    async copy(text: string): Promise<void> {
        this.copied.push(text);
    }
    ofType<T extends HostMessage['type']>(type: T): Array<Extract<HostMessage, { type: T }>> {
        return this.posted.filter((m) => m.type === type) as Array<Extract<HostMessage, { type: T }>>;
    }
}

/** A clock that moves only when a test says so. */
export class FakeScheduler implements Scheduler {
    private pending: Array<{ at: number; run: () => void; cancelled: boolean }> = [];
    now = 0;
    after(ms: number, run: () => void): TimerHandle {
        const timer = { at: this.now + ms, run, cancelled: false };
        this.pending.push(timer);
        return { cancel: () => { timer.cancelled = true; } };
    }
    advance(ms: number): void {
        this.now += ms;
        const due = this.pending.filter((t) => !t.cancelled && t.at <= this.now);
        this.pending = this.pending.filter((t) => !due.includes(t) && !t.cancelled);
        due.forEach((t) => t.run());
    }
    get waiting(): number {
        return this.pending.filter((t) => !t.cancelled).length;
    }
}

/** Lets promise callbacks run. */
export async function settle(): Promise<void> {
    for (let i = 0; i < 5; i++) {
        await new Promise((resolve) => setImmediate(resolve));
    }
}
