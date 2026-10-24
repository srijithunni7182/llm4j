import { strict as assert } from 'assert';
import { test } from 'node:test';
import { CheckRunner } from '../src/check/runner';
import { EngineHost, EngineEnvironment, statusFor } from '../src/engine/host';
import { FakeEngine } from '../src/engine/fake';
import { EngineDiedError, EngineRpcError, EngineUnavailableError } from '../src/engine/errors';
import { EngineCheckRunner, EngineGraphRunner, EngineSource } from '../src/engine/routing';
import { GraphAbortedError } from '../src/graph/errors';
import { GraphRunner } from '../src/graph/runner';
import { FakeEngineProcess } from './engineSupport';
import { FakeScheduler, settle } from './support';

const ok = (result: unknown) => ({ file: 'f', status: 0, result, cached: false });

class Source implements EngineSource {
    constructor(public fake: FakeEngine | undefined, public text: Record<string, string> = {}) {}
    engine() {
        return this.fake;
    }
    unsavedText(file: string) {
        return this.text[file];
    }
}

const signal = new AbortController().signal;
const recordingCheck = (): CheckRunner & { ran: number } => ({ ran: 0, async run() { this.ran++; return '{"fallback":true}'; } });
const recordingGraph = (): GraphRunner & { ran: number } => ({ ran: 0, async run() { this.ran++; return '{"fallback":true}'; } });

test('A12: with the engine, check answers its JSON and the process is not run', async () => {
    const engine = new FakeEngine().on('loom/check', () => ok({ problems: [] }));
    const fallback = recordingCheck();
    const out = await new EngineCheckRunner(new Source(engine), fallback).run({ file: '/p/a.loom', signal });
    assert.deepEqual(JSON.parse(out), { problems: [] });
    assert.equal(fallback.ran, 0);
});

test('the text of an unsaved buffer goes to the engine, and a saved file sends none', async () => {
    const engine = new FakeEngine().on('loom/check', () => ok({}));
    const runner = new EngineCheckRunner(new Source(engine, { '/p/a.loom': 'agent X {}' }), recordingCheck());
    await runner.run({ file: '/p/a.loom', signal });
    await runner.run({ file: '/p/b.loom', signal });
    assert.equal((engine.calls[0].params as { text?: string }).text, 'agent X {}');
    assert.equal((engine.calls[1].params as { text?: string }).text, undefined);
});

test('R19: with no engine, check runs the command as before', async () => {
    const fallback = recordingCheck();
    const out = await new EngineCheckRunner(new Source(undefined), fallback).run({ file: '/p/a.loom', signal });
    assert.equal(fallback.ran, 1);
    assert.deepEqual(JSON.parse(out), { fallback: true });
});

test('R33: when the engine cannot answer (died, unavailable, outside the folders) the command answers', async () => {
    for (const error of [new EngineDiedError(), new EngineUnavailableError('unavailable', 'x'), new EngineRpcError(-32001, 'outside-workspace', 'x')]) {
        const engine = new FakeEngine().failNext(error);
        const fallback = recordingCheck();
        await new EngineCheckRunner(new Source(engine), fallback).run({ file: '/p/a.loom', signal });
        assert.equal(fallback.ran, 1, error.name);
    }
});

test('a request replaced by a newer one is stopped, not failed', async () => {
    const engine = new FakeEngine().on('loom/check', async () => {
        await settle();
        return ok({});
    });
    const runner = new EngineCheckRunner(new Source(engine), recordingCheck());
    const first = runner.run({ file: '/p/a.loom', signal });
    const second = runner.run({ file: '/p/a.loom', signal });
    await assert.rejects(first, GraphAbortedError);
    await second;
});

test('graph: JSON comes from the engine, Mermaid from the command, and a failed engine falls back', async () => {
    const engine = new FakeEngine().on('loom/graph', () => ok({ workflows: [] }));
    const fallback = recordingGraph();
    const runner = new EngineGraphRunner(new Source(engine), fallback);
    assert.deepEqual(JSON.parse(await runner.run({ file: '/p/a.loom', format: 'json', signal })), { workflows: [] });
    assert.equal(fallback.ran, 0);
    await runner.run({ file: '/p/a.loom', format: 'mermaid', signal });
    assert.equal(fallback.ran, 1);
    engine.failNext(new EngineRpcError(-32004, 'engine-failure', 'no graph'));
    await runner.run({ file: '/p/a.loom', format: 'json', signal });
    assert.equal(fallback.ran, 2, 'the command says why a graph cannot be built, in the words it always used');
});

test('the workflow option reaches the engine', async () => {
    const engine = new FakeEngine().on('loom/graph', () => ok({}));
    await new EngineGraphRunner(new Source(engine), recordingGraph()).run({ file: '/p/a.loom', format: 'json', workflow: 'Main', signal });
    assert.equal((engine.calls[0].params as { workflow?: string }).workflow, 'Main');
});

// ---- the host and the one place that says what is limited

function environment(overrides: Partial<EngineEnvironment> = {}): { env: EngineEnvironment; shown: Array<ReturnType<typeof statusFor>>; started: FakeEngineProcess[]; scheduler: FakeScheduler } {
    const shown: Array<ReturnType<typeof statusFor>> = [];
    const started: FakeEngineProcess[] = [];
    const scheduler = new FakeScheduler();
    const env: EngineEnvironment = {
        javaPath: () => 'java',
        jarPath: () => '/ext/bin/weave.jar',
        jarExists: () => true,
        roots: () => ['/work'],
        unsavedText: () => undefined,
        startProcess: () => {
            const p = new FakeEngineProcess();
            started.push(p);
            return p;
        },
        scheduler,
        showStatus: (s) => shown.push(s),
        log: () => undefined,
        ...overrides,
    };
    return { env, shown, started, scheduler };
}

test('R33: the status line says what is limited, in one place, and nothing when all is well', () => {
    assert.equal(statusFor('ready', ''), undefined);
    assert.equal(statusFor('stopped', ''), undefined);
    assert.match(statusFor('unavailable', 'Java was not found')!.text, /engine unavailable \(using commands\)/);
    assert.match(statusFor('unavailable', 'Java was not found')!.tooltip, /Java was not found/);
    assert.match(statusFor('mismatch', 'engine v2, extension v1')!.text, /reinstall the extension/);
    assert.match(statusFor('starting', '')!.text, /starting engine/);
});

test('the host starts the engine, asks about the open folders, and hides the status once it is ready', async () => {
    const t = environment();
    const host = new EngineHost(t.env);
    host.start();
    assert.equal(t.started.length, 1);
    assert.deepEqual(t.started[0].last('initialize').params, { roots: ['/work'] });
    assert.match(t.shown[t.shown.length - 1]!.text, /starting engine/);
    t.started[0].handshake();
    await settle();
    assert.equal(t.shown[t.shown.length - 1], undefined);
    assert.ok(host.engine());
});

test('R19: with no weave.jar the engine is absent and the status says to reinstall', () => {
    const t = environment({ jarExists: () => false });
    const host = new EngineHost(t.env);
    host.start();
    assert.equal(host.engine(), undefined);
    assert.equal(t.started.length, 0);
    assert.match(t.shown[t.shown.length - 1]!.text, /reinstall/);
});

test('R19: Java that cannot start leaves the host without an engine and says so', () => {
    const t = environment({ startProcess: () => { throw new Error('spawn java ENOENT'); } });
    const host = new EngineHost(t.env);
    host.start();
    assert.equal(host.engine(), undefined);
    assert.match(t.shown[t.shown.length - 1]!.text, /unavailable/);
    assert.match(t.shown[t.shown.length - 1]!.tooltip, /ENOENT/);
});

test('restarting replaces the engine: the old process is stopped and a new one started', async () => {
    const t = environment();
    const host = new EngineHost(t.env);
    host.start();
    t.started[0].handshake();
    await settle();
    host.start();
    assert.ok(t.started[0].killed);
    assert.equal(t.started.length, 2);
});

test('disposing the host stops the engine and clears the status', async () => {
    const t = environment();
    const host = new EngineHost(t.env);
    host.start();
    t.started[0].handshake();
    await settle();
    host.dispose();
    assert.ok(t.started[0].killed);
    assert.equal(host.engine(), undefined);
});
