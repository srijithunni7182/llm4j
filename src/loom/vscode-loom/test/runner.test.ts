import test from 'node:test';
import assert from 'node:assert/strict';
import { GraphAbortedError, GraphExitError, GraphTimeoutError, OutputTooLargeError, RuntimeMissingError } from '../src/graph/errors';
import { MAX_GRAPH_BYTES } from '../src/graph/model';
import { WeaveGraphRunner } from '../src/graph/runner';
import { FakeChild } from './support';

function runner(options: { timeoutMs?: number; jar?: boolean } = {}) {
    const children: FakeChild[] = [];
    const calls: Array<{ command: string; args: string[] }> = [];
    const r = new WeaveGraphRunner(
        { javaPath: 'java', jarPath: '/ext/bin/weave.jar', timeoutMs: options.timeoutMs ?? 5000 },
        (command, args) => {
            calls.push({ command, args });
            const child = new FakeChild();
            children.push(child);
            return child.asChild();
        },
        () => options.jar !== false,
    );
    return { r, children, calls };
}

const request = (extra: Record<string, unknown> = {}) => ({ file: '/p/main.loom', format: 'json' as const, signal: new AbortController().signal, ...extra });

/** Starts a run and lets the fake process finish, so nothing is left waiting. */
async function started(r: WeaveGraphRunner, children: FakeChild[], req: ReturnType<typeof request>): Promise<void> {
    const run = r.run(req);
    children[0].emit('exit', 0);
    await run;
}

test('the command line is java -jar weave.jar graph <file> --format json, one argument each', async () => {
    const { r, calls, children } = runner();
    await started(r, children, request());
    assert.deepEqual(calls[0], { command: 'java', args: ['-jar', '/ext/bin/weave.jar', 'graph', '/p/main.loom', '--format', 'json'] });
});

test('VR.1: a file name with spaces and shell characters is still one argument', async () => {
    const { r, calls, children } = runner();
    const nasty = '/p/my scripts/a; rm -rf ~ $(whoami) `x`.loom';
    await started(r, children, request({ file: nasty }));
    assert.equal(calls[0].args[3], nasty);
    assert.equal(calls[0].args.length, 6);
});

test('a workflow name adds --workflow', async () => {
    const { r, calls, children } = runner();
    await started(r, children, request({ format: 'mermaid', workflow: 'Main' }));
    assert.deepEqual(calls[0].args.slice(-4), ['--format', 'mermaid', '--workflow', 'Main']);
});

test('what the process prints is returned when it exits with 0', async () => {
    const { r, children } = runner();
    const run = r.run(request());
    children[0].stdout.emit('data', Buffer.from('{"a":'));
    children[0].stdout.emit('data', Buffer.from('1}'));
    children[0].emit('exit', 0);
    assert.equal(await run, '{"a":1}');
});

test('exit code 2 gives the message the command printed, with its file and line', async () => {
    const { r, children } = runner();
    const run = r.run(request());
    children[0].stderr.emit('data', Buffer.from('Error: /p/bad.loom:2: Parse error at line 2 col 3 (RBRACE): Expect \'->\'\n'));
    children[0].emit('exit', 2);
    await assert.rejects(run, (e: Error) => {
        assert.ok(e instanceof GraphExitError);
        assert.equal((e as GraphExitError).code, 2);
        assert.deepEqual((e as GraphExitError).location(), { file: '/p/bad.loom', line: 2 });
        return /Parse error/.test(e.message);
    });
});

test('a process that cannot start because java is missing says how to fix it', async () => {
    const { r, children } = runner();
    const run = r.run(request());
    children[0].emit('error', new Error('spawn java ENOENT'));
    await assert.rejects(run, (e: Error) => e instanceof RuntimeMissingError && e.what === 'java' && /loom\.graph\.javaPath/.test(e.message));
});

test('a throw while starting the process is also reported as java missing', async () => {
    const r = new WeaveGraphRunner({ javaPath: 'java', jarPath: '/j', timeoutMs: 1000 }, () => { throw new Error('boom'); }, () => true);
    await assert.rejects(r.run(request()), (e: Error) => e instanceof RuntimeMissingError);
});

test('a process that takes too long is killed and the message says so', async () => {
    const { r, children } = runner({ timeoutMs: 20 });
    await assert.rejects(r.run(request()), (e: Error) => e instanceof GraphTimeoutError && /longer than 0 s|stopped/.test(e.message));
    assert.equal(children[0].killed, true);
});

test('the timeout message names the setting and the seconds', () => {
    assert.match(new GraphTimeoutError(30000).message, /30 s.*loom\.graph\.timeoutMs/);
});

test('aborting kills the process and rejects', async () => {
    const { r, children } = runner();
    const controller = new AbortController();
    const run = r.run(request({ signal: controller.signal }));
    controller.abort();
    await assert.rejects(run, GraphAbortedError);
    assert.equal(children[0].killed, true);
});

test('a request that is already aborted never starts a process', async () => {
    const { r, calls } = runner();
    const controller = new AbortController();
    controller.abort();
    await assert.rejects(r.run(request({ signal: controller.signal })), GraphAbortedError);
    assert.equal(calls.length, 0);
});

test('output over the limit stops the process', async () => {
    const { r, children } = runner();
    const run = r.run(request());
    children[0].stdout.emit('data', Buffer.alloc(MAX_GRAPH_BYTES + 1));
    await assert.rejects(run, OutputTooLargeError);
    assert.equal(children[0].killed, true);
});

test('finishing late after a timeout changes nothing', async () => {
    const { r, children } = runner({ timeoutMs: 10 });
    const run = r.run(request());
    await assert.rejects(run, GraphTimeoutError);
    children[0].emit('exit', 0);
    children[0].stdout.emit('data', Buffer.from('{}'));
});

test('a missing weave.jar is reported before java is started', async () => {
    const { r, calls } = runner({ jar: false });
    await assert.rejects(r.verifyRuntime(), (e: Error) => e instanceof RuntimeMissingError && e.what === 'jar' && /Reinstall/.test(e.message));
    assert.equal(calls.length, 0);
});

test('verifying the runtime runs java -version and accepts exit 0', async () => {
    const { r, children, calls } = runner();
    const check = r.verifyRuntime();
    children[0].emit('exit', 0);
    await check;
    assert.deepEqual(calls[0].args, ['-version']);
});

test('verifying the runtime fails when java is missing or exits non-zero', async () => {
    const missing = runner();
    const first = missing.r.verifyRuntime();
    missing.children[0].emit('error', new Error('ENOENT'));
    await assert.rejects(first, (e: Error) => e instanceof RuntimeMissingError && e.what === 'java');

    const broken = runner();
    const second = broken.r.verifyRuntime();
    broken.children[0].emit('exit', 1);
    await assert.rejects(second, RuntimeMissingError);
});
