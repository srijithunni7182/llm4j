import { strict as assert } from 'assert';
import { test } from 'node:test';
import { ProcessEngineClient, EngineState } from '../src/engine/client';
import {
    EngineCancelledError,
    EngineDiedError,
    EngineRpcError,
    EngineStaleError,
    EngineTimeoutError,
    EngineUnavailableError,
} from '../src/engine/errors';
import { encodeFrame, FrameDecoder, FrameError, MAX_FRAME_BYTES } from '../src/engine/framing';
import { FakeEngineProcess, processes } from './engineSupport';
import { FakeScheduler, settle } from './support';

function setup(options: { maxFailures?: number; timeout?: number; start?: () => FakeEngineProcess } = {}) {
    const p = processes();
    const scheduler = new FakeScheduler();
    const states: EngineState[] = [];
    const client = new ProcessEngineClient({
        start: options.start ?? p.start,
        roots: () => ['/work'],
        scheduler,
        backoffMs: [100, 200, 400],
        maxFailures: options.maxFailures ?? 3,
        requestTimeoutMs: options.timeout ?? 5000,
    });
    client.onDidChangeState((s) => states.push(s));
    return { p, scheduler, states, client };
}

async function ready(t: ReturnType<typeof setup>): Promise<FakeEngineProcess> {
    t.client.start();
    const proc = t.p.made[t.p.made.length - 1];
    proc.handshake();
    await settle();
    return proc;
}

test('R13: the client starts the engine, sends the folders it has open, and is ready after the handshake', async () => {
    const t = setup();
    t.client.start();
    assert.equal(t.client.state, 'starting');
    const proc = t.p.made[0];
    assert.deepEqual(proc.last('initialize').params, { roots: ['/work'] });
    proc.handshake();
    await settle();
    assert.equal(t.client.state, 'ready');
    assert.deepEqual(t.states, ['starting', 'ready']);
});

test('R13: an engine that speaks another protocol is refused, with the reason', async () => {
    const t = setup();
    t.client.start();
    t.p.made[0].handshake(2);
    await settle();
    assert.equal(t.client.state, 'mismatch');
    await assert.rejects(t.client.request('loom/check', { file: '/work/a.loom' }), (e: Error) => e instanceof EngineUnavailableError && /protocol/.test(e.message));
    assert.equal(t.p.made.length, 1, 'no restart: a restart would speak the same protocol');
});

test('a request made while the engine is starting waits for it and then goes out', async () => {
    const t = setup();
    t.client.start();
    const proc = t.p.made[0];
    const answer = t.client.request('loom/check', { file: '/work/a.loom' });
    assert.equal(proc.requests('loom/check').length, 0, 'not sent before the handshake');
    proc.handshake();
    await settle();
    const sent = proc.last('loom/check');
    proc.reply(sent.id as number, { file: '/work/a.loom', status: 0, result: { ok: true }, cached: false });
    assert.deepEqual((await answer).result, { ok: true });
});

test('requests are matched to answers by id, whatever order the answers come in', async () => {
    const t = setup();
    const proc = await ready(t);
    const a = t.client.request('loom/check', { file: '/work/a.loom' });
    const b = t.client.request('loom/check', { file: '/work/b.loom' });
    const [first, second] = proc.requests('loom/check');
    proc.reply(second.id as number, { file: 'b', status: 0, result: 'B', cached: false });
    proc.reply(first.id as number, { file: 'a', status: 0, result: 'A', cached: false });
    assert.equal((await a).result, 'A');
    assert.equal((await b).result, 'B');
});

test('a typed error from the server becomes an EngineRpcError with its kind', async () => {
    const t = setup();
    const proc = await ready(t);
    const answer = t.client.request('loom/check', { file: '/elsewhere/a.loom' });
    proc.fail(proc.last('loom/check').id as number, -32001, 'outside-workspace', 'not in the workspace');
    await assert.rejects(answer, (e: Error) => e instanceof EngineRpcError && e.kind === 'outside-workspace' && e.code === -32001);
});

test('R11: asking again about the same file withdraws the earlier request, on the server too', async () => {
    const t = setup();
    const proc = await ready(t);
    const first = t.client.latest('a.loom', 'loom/review', { file: '/work/a.loom' });
    const second = t.client.latest('a.loom', 'loom/review', { file: '/work/a.loom' });
    await assert.rejects(first, EngineCancelledError);
    const [one, two] = proc.requests('loom/review');
    assert.ok(proc.sent.some((s) => s.method === 'loom/cancel' && s.params.id === one.id), 'the server was told to drop it');
    proc.reply(two.id as number, { file: 'a', status: 0, result: 'new', cached: false, requestVersion: two.params.version });
    assert.equal((await second).result, 'new');
});

test('R11: an answer for an older document version is discarded', async () => {
    const t = setup();
    const proc = await ready(t);
    const old = t.client.latest('a.loom', 'loom/review', { file: '/work/a.loom' });
    const oldRequest = proc.last('loom/review');
    // the server answers the old one with its old version after a newer one was asked
    const newer = t.client.latest('a.loom', 'loom/review', { file: '/work/a.loom' });
    proc.reply(oldRequest.id as number, { file: 'a', status: 0, result: 'old', cached: false, requestVersion: oldRequest.params.version });
    await assert.rejects(old, (e: Error) => e instanceof EngineCancelledError || e instanceof EngineStaleError);
    const current = proc.last('loom/review');
    proc.reply(current.id as number, { file: 'a', status: 0, result: 'new', cached: false, requestVersion: current.params.version });
    assert.equal((await newer).result, 'new');
});

test('R11: an answer that carries the wrong version is stale even if it arrives first', async () => {
    const t = setup();
    const proc = await ready(t);
    const answer = t.client.latest('a.loom', 'loom/review', { file: '/work/a.loom' });
    const req = proc.last('loom/review');
    proc.reply(req.id as number, { file: 'a', status: 0, result: 'x', cached: false, requestVersion: (req.params.version as number) - 1 });
    await assert.rejects(answer, EngineStaleError);
});

test('different files are independent: asking about b does not withdraw a', async () => {
    const t = setup();
    const proc = await ready(t);
    const a = t.client.latest('a.loom', 'loom/check', { file: '/work/a.loom' });
    const b = t.client.latest('b.loom', 'loom/check', { file: '/work/b.loom' });
    const [ra, rb] = proc.requests('loom/check');
    proc.reply(ra.id as number, { file: 'a', status: 0, result: 'A', cached: false, requestVersion: ra.params.version });
    proc.reply(rb.id as number, { file: 'b', status: 0, result: 'B', cached: false, requestVersion: rb.params.version });
    assert.equal((await a).result, 'A');
    assert.equal((await b).result, 'B');
});

test('an aborted request is withdrawn and the server told', async () => {
    const t = setup();
    const proc = await ready(t);
    const abort = new AbortController();
    const answer = t.client.request('loom/graph', { file: '/work/a.loom' }, { signal: abort.signal });
    abort.abort();
    await assert.rejects(answer, EngineCancelledError);
    assert.ok(proc.sent.some((s) => s.method === 'loom/cancel'));
});

test('a request the engine does not answer in time fails with a timeout and is cancelled', async () => {
    const t = setup({ timeout: 1000 });
    const proc = await ready(t);
    const answer = t.client.request('loom/review', { file: '/work/a.loom' });
    t.scheduler.advance(1001);
    await assert.rejects(answer, EngineTimeoutError);
    assert.ok(proc.sent.some((s) => s.method === 'loom/cancel'));
});

test('R16: a dead engine fails the requests in flight and is started again after a wait', async () => {
    const t = setup();
    const proc = await ready(t);
    const answer = t.client.request('loom/review', { file: '/work/a.loom' });
    proc.exit(1);
    await assert.rejects(answer, EngineDiedError);
    assert.equal(t.client.state, 'restarting');
    assert.equal(t.p.made.length, 1, 'not before the wait');
    t.scheduler.advance(100);
    assert.equal(t.p.made.length, 2);
    t.p.made[1].handshake();
    await settle();
    assert.equal(t.client.state, 'ready');
});

test('R16: a request made while the engine restarts is kept for the new one', async () => {
    const t = setup();
    const proc = await ready(t);
    proc.exit(1);
    const answer = t.client.request('loom/check', { file: '/work/a.loom' });
    t.scheduler.advance(100);
    const next = t.p.made[1];
    next.handshake();
    await settle();
    next.reply(next.last('loom/check').id as number, { file: 'a', status: 0, result: 'ok', cached: false });
    assert.equal((await answer).result, 'ok');
});

test('R16: waits grow with each failure and the engine is given up after the limit', async () => {
    const t = setup({ maxFailures: 3 });
    t.client.start();
    const waits: number[] = [];
    for (let i = 0; i < 3; i++) {
        t.p.made[i].exit(1);
        assert.equal(t.client.state, 'restarting');
        const before = t.scheduler.now;
        let step = 0;
        while (t.p.made.length === i + 1) {
            t.scheduler.advance(50);
            step += 50;
        }
        waits.push(step);
        void before;
    }
    assert.deepEqual(waits, [100, 200, 400]);
    t.p.made[3].exit(1);
    assert.equal(t.client.state, 'unavailable');
    await assert.rejects(t.client.request('loom/check', { file: '/work/a.loom' }), EngineUnavailableError);
});

test('R16: an engine that answers a request again counts as healthy: earlier failures are forgotten', async () => {
    const t = setup({ maxFailures: 2 });
    for (let round = 0; round < 4; round++) {
        if (round === 0) {
            t.client.start();
        } else {
            t.scheduler.advance(1000);
        }
        const proc = t.p.made[t.p.made.length - 1];
        proc.handshake();
        await settle();
        const answer = t.client.request('loom/check', { file: '/work/a.loom' });
        proc.reply(proc.last('loom/check').id as number, { file: 'a', status: 0, result: 'ok', cached: false });
        await answer;
        proc.exit(1);
    }
    assert.notEqual(t.client.state, 'unavailable');
});

test('a Java that cannot be started makes the engine unavailable at once, with the reason', async () => {
    const t = setup({
        start: () => {
            throw new Error('spawn java ENOENT');
        },
    });
    t.client.start();
    assert.equal(t.client.state, 'unavailable');
    await assert.rejects(t.client.request('loom/check', { file: '/work/a.loom' }), (e: Error) => /ENOENT/.test(e.message) || e instanceof EngineUnavailableError);
});

test('output that is not framed kills the engine, which is then restarted', async () => {
    const t = setup();
    const proc = await ready(t);
    proc.emitBytes(Buffer.from('this is not a frame\r\n\r\n'));
    assert.ok(proc.killed);
    assert.equal(t.client.state, 'restarting');
});

test('what the engine writes to standard error is kept for the log', async () => {
    const t = setup();
    const proc = await ready(t);
    proc.stderr('INFO: loading\nWARN: slow\n');
    assert.deepEqual(t.client.log(), ['INFO: loading', 'WARN: slow']);
});

test('disposing asks the engine to shut down, kills it and refuses further requests', async () => {
    const t = setup();
    const proc = await ready(t);
    const waiting = t.client.request('loom/review', { file: '/work/a.loom' });
    t.client.dispose();
    await assert.rejects(waiting, EngineUnavailableError);
    assert.ok(proc.sent.some((s) => s.method === 'loom/shutdown'));
    assert.ok(proc.killed);
    assert.equal(t.client.state, 'stopped');
    await assert.rejects(t.client.request('loom/check', { file: '/work/a.loom' }), EngineUnavailableError);
});

// ---- framing

test('framing: a message split across chunks is held until whole, and several in one chunk come out together', () => {
    const d = new FrameDecoder();
    const one = encodeFrame({ a: 1 });
    const two = encodeFrame({ b: '✓' });
    const all = Buffer.concat([one, two]);
    const out: string[] = [];
    for (let i = 0; i < all.length; i += 5) {
        out.push(...d.push(all.subarray(i, i + 5)));
    }
    assert.deepEqual(out.map((s) => JSON.parse(s)), [{ a: 1 }, { b: '✓' }]);
    assert.deepEqual(new FrameDecoder().push(all).length, 2);
});

test('framing: a frame over the limit, a header without a length and a runaway header are errors', () => {
    assert.throws(() => new FrameDecoder().push(Buffer.from(`Content-Length: ${MAX_FRAME_BYTES + 1}\r\n\r\n`)), FrameError);
    assert.throws(() => new FrameDecoder().push(Buffer.from('X-Other: 1\r\n\r\n')), FrameError);
    assert.throws(() => new FrameDecoder().push(Buffer.alloc(9000, 65)), FrameError);
});

test('a request that times out is followed by a ping; no answer means the engine is restarted', async () => {
    const t = setup({ timeout: 1000 });
    const proc = await ready(t);
    const p = t.client.request('loom/check', { file: '/work/a.loom' });
    p.catch(() => undefined);
    t.scheduler.advance(1000);
    await assert.rejects(p, (e: Error) => e instanceof EngineTimeoutError);
    assert.ok(proc.last('loom/ping'), 'a ping was sent');
    assert.ok(!proc.killed, 'not yet: it has time to answer');
    t.scheduler.advance(2000);
    assert.ok(proc.killed);
    assert.equal(t.client.state, 'restarting');
});

test('a slow engine that answers the ping is left alone', async () => {
    const t = setup({ timeout: 1000 });
    const proc = await ready(t);
    const p = t.client.request('loom/check', { file: '/work/a.loom' });
    p.catch(() => undefined);
    t.scheduler.advance(1000);
    await assert.rejects(p);
    const ping = proc.last('loom/ping');
    proc.reply(ping.id as number, { busy: 1, queued: 0, abandoned: 0 });
    await settle();
    t.scheduler.advance(5000);
    assert.ok(!proc.killed);
    assert.equal(t.client.state, 'ready');
});
