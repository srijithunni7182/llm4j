import { strict as assert } from 'assert';
import { test } from 'node:test';
import { DEFAULT_SERVE, EngineEnvironment, EngineHost, ServeSettings, statusFor } from '../src/engine/host';
import { checkJava, JavaCheck, parseJavaMajor } from '../src/engine/javaProbe';
import { FakeEngineProcess } from './engineSupport';
import { FakeScheduler, settle } from './support';

type Shown = ReturnType<typeof statusFor>;

function setup(overrides: Partial<EngineEnvironment> = {}) {
    const shown: Shown[] = [];
    const started: FakeEngineProcess[] = [];
    const served: ServeSettings[] = [];
    const logs: string[] = [];
    const scheduler = new FakeScheduler();
    const env: EngineEnvironment = {
        javaPath: () => 'java',
        jarPath: () => '/ext/bin/weave.jar',
        jarExists: () => true,
        roots: () => ['/work'],
        unsavedText: () => undefined,
        startProcess: (_java, _jar, serve) => {
            served.push(serve);
            const p = new FakeEngineProcess();
            started.push(p);
            return p;
        },
        scheduler,
        showStatus: (s) => shown.push(s),
        log: (l) => logs.push(l),
        ...overrides,
    };
    return { env, shown, started, served, logs, scheduler, last: (): Shown => shown[shown.length - 1] };
}

const noJava: JavaCheck = { ok: false, reason: 'missing', detail: 'spawn java ENOENT' };
const oldJava: JavaCheck = { ok: false, reason: 'too-old', major: 11, detail: 'Java 11 is too old; the engine needs 17 or newer' };

test('R33: no Java at all stops the engine and says so in one line that opens the fix steps', async () => {
    const t = setup({ probeJava: async () => noJava });
    const host = new EngineHost(t.env);
    host.start();
    await settle();
    assert.equal(host.engine(), undefined);
    assert.ok(t.started[0].killed, 'the engine that could not run is stopped');
    assert.equal(t.last()!.text, 'Loom: Java 17+ not found (limited mode)');
    assert.equal(t.last()!.command, 'loom.javaHelp');
    assert.match(t.last()!.tooltip, /ENOENT/);
    assert.match(t.last()!.tooltip, /Syntax colours, snippets/);
});

test('R33: a Java that is too old says which one and what is needed', async () => {
    const t = setup({ probeJava: async () => oldJava });
    new EngineHost(t.env).start();
    await settle();
    assert.equal(t.last()!.text, 'Loom: Java 11 is too old (needs 17+, limited mode)');
    assert.equal(t.last()!.command, 'loom.javaHelp');
});

test('R33: a Java that works leaves nothing to say once the engine is ready', async () => {
    const t = setup({ probeJava: async () => ({ ok: true, major: 21 }) });
    const host = new EngineHost(t.env);
    host.start();
    t.started[0].handshake();
    await settle();
    assert.equal(t.last(), undefined);
    assert.ok(host.engine());
});

test('R33: an answer about an old start does not stop the new one', async () => {
    let answer: (c: JavaCheck) => void = () => undefined;
    const t = setup({ probeJava: () => new Promise<JavaCheck>((resolve) => { answer = resolve; }) });
    const host = new EngineHost(t.env);
    host.start();
    const first = answer;
    host.start(); // a restart, e.g. after loom.javaPath changed
    first(noJava);
    await settle();
    assert.ok(host.engine(), 'the second start is untouched');
    assert.notEqual(t.last()?.text, 'Loom: Java 17+ not found (limited mode)');
});

test('R33: an engine that keeps dying ends as "unavailable (using commands)", with no popup and one line at a time', async () => {
    const t = setup();
    const host = new EngineHost(t.env);
    host.start();
    for (let i = 0; i < 12 && host.state !== 'unavailable'; i++) {
        t.started[t.started.length - 1].exit(1);
        t.scheduler.advance(10_000);
        await settle();
    }
    assert.equal(host.state, 'unavailable');
    assert.equal(host.engine(), undefined);
    assert.equal(t.last()!.text, 'Loom: engine unavailable (using commands)');
    // only status lines were ever produced, each a state the table names
    const texts = new Set(t.shown.map((s) => s?.text));
    for (const text of texts) {
        assert.match(text ?? 'nothing', /starting engine|engine unavailable|nothing/);
    }
});

test('R33: a protocol mismatch says which versions and asks for a reinstall', async () => {
    const t = setup();
    const host = new EngineHost(t.env);
    host.start();
    t.started[0].handshake(2);
    await settle();
    assert.equal(host.state, 'mismatch');
    assert.equal(t.last()!.text, 'Loom: reinstall the extension (engine v2, extension v1)');
    assert.equal(host.engine(), undefined);
});

test('R33: a slow engine is not restarted while it still answers a ping, and is when it does not', async () => {
    const t = setup();
    const host = new EngineHost(t.env);
    host.start();
    t.started[0].handshake();
    await settle();
    const engine = host.engine()!;
    const slow = engine.request('loom/check', { file: '/work/a.loom' });
    slow.catch(() => undefined);
    t.scheduler.advance(DEFAULT_SERVE.timeoutMs + 2000);
    await assert.rejects(slow, /timed out|timeout/i);
    t.scheduler.advance(5000);
    assert.ok(t.started[0].killed, 'it did not answer the ping');
    assert.equal(t.last()!.text, 'Loom: starting engine…');
});

test('R31: the engine is started with the memory and time limits the settings give, and waits a little longer than it asks', async () => {
    const t = setup({ serve: () => ({ memoryMb: 128, timeoutMs: 4000 }) });
    const host = new EngineHost(t.env);
    host.start();
    assert.deepEqual(t.served[0], { memoryMb: 128, timeoutMs: 4000 });
    t.started[0].handshake();
    await settle();
    const p = host.engine()!.request('loom/check', { file: '/work/a.loom' });
    p.catch(() => undefined);
    t.scheduler.advance(5999);
    await settle();
    assert.equal(t.started[0].killed, false, 'not yet: the engine\'s own limit (4 s) plus the margin (2 s)');
    t.scheduler.advance(2);
    await assert.rejects(p);
});

test('R19: the Java version is read from what `java -version` prints', () => {
    assert.equal(parseJavaMajor('openjdk version "17.0.9" 2023-10-17\nOpenJDK Runtime Environment'), 17);
    assert.equal(parseJavaMajor('java version "1.8.0_392"'), 8);
    assert.equal(parseJavaMajor('openjdk version "21" 2023-09-19'), 21);
    assert.equal(parseJavaMajor('openjdk version "22-ea" 2024-03-19'), 22);
    assert.equal(parseJavaMajor('Picked up JAVA_TOOL_OPTIONS: -Dx=y\nopenjdk version "11.0.2"'), 11);
    assert.equal(parseJavaMajor('command not found'), undefined);
});

test('R19: checkJava tells missing, unreadable, too old and fine apart', async () => {
    assert.deepEqual(await checkJava('java', async () => ({ output: 'openjdk version "17.0.1"' })), { ok: true, major: 17 });
    const old = await checkJava('java', async () => ({ output: 'openjdk version "1.8.0_1"' }));
    assert.ok(!old.ok && old.reason === 'too-old' && old.major === 8);
    const missing = await checkJava('/no/java', async () => ({ error: 'spawn /no/java ENOENT' }));
    assert.ok(!missing.ok && missing.reason === 'missing');
    const odd = await checkJava('java', async () => ({ output: 'hello' }));
    assert.ok(!odd.ok && odd.reason === 'unreadable');
});
