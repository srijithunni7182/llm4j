import test from 'node:test';
import assert from 'node:assert/strict';
import { CheckDiagnostic, CheckParseError, MAX_CHECK_BYTES, parseCheck } from '../src/check/model';
import { CHECK_DEBOUNCE_MS, CheckDiagnosticsController, DiagnosticsHost } from '../src/check/controller';
import { CheckRequest, CheckRunner, WeaveCheckRunner } from '../src/check/runner';
import { GraphAbortedError, GraphExitError, RuntimeMissingError } from '../src/graph/errors';
import { WeaveGraphRunner } from '../src/graph/runner';
import { FakeChild, FakeScheduler, settle } from './support';

const result = (diagnostics: unknown[] = [], extra: Record<string, unknown> = {}) =>
    JSON.stringify({ version: 1, file: '/p/main.loom', ok: true, diagnostics, notSetYet: [], ...extra });

const diag = (line: number, message: string, severity = 'error', file = '/p/main.loom') => ({ severity, file, line, message });

// ── the result ───────────────────────────────────────────────────────────

test('R1.1: a result with problems and names not set yet is read', () => {
    const parsed = parseCheck(result([diag(3, 'agent A: model needs a key', 'warning')], { ok: true, notSetYet: ['GEMINI_API_KEY'] }));
    assert.equal(parsed.diagnostics[0].line, 3);
    assert.equal(parsed.diagnostics[0].severity, 'warning');
    assert.deepEqual(parsed.notSetYet, ['GEMINI_API_KEY']);
});

test('notSetYet may be missing, and the rest of the shape is checked', () => {
    assert.deepEqual(parseCheck(JSON.stringify({ version: 1, file: 'f', ok: true, diagnostics: [] })).notSetYet, []);
    for (const [bad, expected] of [
        ['not json', /did not print JSON/],
        ['[]', /JSON object/],
        [JSON.stringify({ version: 2, file: 'f', ok: true, diagnostics: [] }), /version 2 is not supported/],
        [JSON.stringify({ version: 1, ok: true, diagnostics: [] }), /file should be a string/],
        [JSON.stringify({ version: 1, file: 'f', diagnostics: [] }), /ok should be true or false/],
        [JSON.stringify({ version: 1, file: 'f', ok: true }), /diagnostics should be a list/],
        [result([diag(1, 'x', 'info')]), /severity should be "error" or "warning"/],
        [result([{ severity: 'error', file: 'f', line: 'one', message: 'x' }]), /needs a file, a line and a message/],
        [result([], { notSetYet: [1] }), /notSetYet should be a list of names/],
    ] as Array<[string, RegExp]>) {
        assert.throws(() => parseCheck(bad), (e: Error) => e instanceof CheckParseError && expected.test(e.message), bad);
    }
});

test('a result over the limit is refused before it is parsed', () => {
    assert.throws(() => parseCheck(' '.repeat(MAX_CHECK_BYTES + 1)), /larger than 2 MB/);
});

// ── the runner ───────────────────────────────────────────────────────────

function weaveRunner() {
    const calls: Array<{ command: string; args: string[] }> = [];
    const children: FakeChild[] = [];
    const weave = new WeaveGraphRunner(
        { javaPath: 'java', jarPath: '/ext/bin/weave.jar', timeoutMs: 5000 },
        (command, args) => {
            calls.push({ command, args });
            const child = new FakeChild();
            children.push(child);
            return child.asChild();
        },
        () => true,
    );
    return { runner: new WeaveCheckRunner(weave), calls, children };
}

test('R1.1: the command is java -jar weave.jar check <file> --format json --no-env, one argument each', async () => {
    const { runner, calls, children } = weaveRunner();
    const nasty = '/p/my scripts/a; rm -rf ~ $(whoami).loom';
    const run = runner.run({ file: nasty, signal: new AbortController().signal });
    children[0].stdout.emit('data', Buffer.from('{}'));
    children[0].emit('exit', 0);

    assert.equal(await run, '{}');
    assert.deepEqual(calls[0], { command: 'java', args: ['-jar', '/ext/bin/weave.jar', 'check', nasty, '--format', 'json', '--no-env'] });
});

test('exit 2 (problems found) still gives the JSON; any other exit is an error', async () => {
    const two = weaveRunner();
    const run = two.runner.run({ file: '/p/a.loom', signal: new AbortController().signal });
    two.children[0].stdout.emit('data', Buffer.from('{"v":2}'));
    two.children[0].emit('exit', 2);
    assert.equal(await run, '{"v":2}');

    const one = weaveRunner();
    const failing = one.runner.run({ file: '/p/a.loom', signal: new AbortController().signal });
    one.children[0].stderr.emit('data', Buffer.from('boom'));
    one.children[0].emit('exit', 1);
    await assert.rejects(failing, (e: Error) => e instanceof GraphExitError && /boom/.test(e.message));
});

// ── the controller ───────────────────────────────────────────────────────

class FakeCheckRunner implements CheckRunner {
    requests: CheckRequest[] = [];
    private waiting: Array<{ resolve: (t: string) => void; reject: (e: Error) => void }> = [];
    run(request: CheckRequest): Promise<string> {
        this.requests.push(request);
        return new Promise((resolve, reject) => {
            this.waiting.push({ resolve, reject });
            request.signal.addEventListener('abort', () => reject(new GraphAbortedError()));
        });
    }
    finish(text: string, index = this.waiting.length - 1): void { this.waiting[index].resolve(text); }
    fail(error: Error, index = this.waiting.length - 1): void { this.waiting[index].reject(error); }
}

class FakeDiagnostics implements DiagnosticsHost {
    shown = new Map<string, CheckDiagnostic[]>();
    sets: Array<{ file: string; diagnostics: CheckDiagnostic[] }> = [];
    notices: string[] = [];
    set(file: string, diagnostics: CheckDiagnostic[]): void { this.shown.set(file, diagnostics); this.sets.push({ file, diagnostics }); }
    clear(file: string): void { this.shown.delete(file); }
    notify(message: string): void { this.notices.push(message); }
}

function setup() {
    const runner = new FakeCheckRunner();
    const host = new FakeDiagnostics();
    const scheduler = new FakeScheduler();
    const controller = new CheckDiagnosticsController({ runner, host, scheduler });
    return { runner, host, scheduler, controller };
}

test('R1.2: a file is checked after the debounce and its problems are shown as they were found', async () => {
    const s = setup();
    s.controller.request('/p/main.loom');
    assert.equal(s.runner.requests.length, 0, 'nothing runs before the debounce');
    s.scheduler.advance(CHECK_DEBOUNCE_MS);
    s.runner.finish(result([diag(4, 'workflow Main: x is set here and never used', 'warning'), diag(9, 'Parse error')]));
    await settle();

    assert.deepEqual(s.host.shown.get('/p/main.loom'), [
        { severity: 'warning', file: '/p/main.loom', line: 4, message: 'workflow Main: x is set here and never used' },
        { severity: 'error', file: '/p/main.loom', line: 9, message: 'Parse error' },
    ]);
    assert.deepEqual(s.runner.requests.map((r) => r.file), ['/p/main.loom']);
});

test('a script with no problems clears what was shown', async () => {
    const s = setup();
    s.controller.request('/p/main.loom');
    s.scheduler.advance(CHECK_DEBOUNCE_MS);
    s.runner.finish(result([diag(1, 'x')]));
    await settle();
    s.controller.request('/p/main.loom');
    s.scheduler.advance(CHECK_DEBOUNCE_MS);
    s.runner.finish(result([]));
    await settle();

    assert.deepEqual(s.host.shown.get('/p/main.loom'), []);
});

test('several requests inside the debounce make one run, and a newer run stops an older one', async () => {
    const s = setup();
    for (let i = 0; i < 5; i++) {
        s.controller.request('/p/main.loom');
        s.scheduler.advance(CHECK_DEBOUNCE_MS - 50);
    }
    assert.equal(s.runner.requests.length, 0);
    s.scheduler.advance(CHECK_DEBOUNCE_MS);
    assert.equal(s.runner.requests.length, 1);

    s.controller.request('/p/main.loom');
    s.scheduler.advance(CHECK_DEBOUNCE_MS);
    assert.equal(s.runner.requests.length, 2);
    assert.ok(s.runner.requests[0].signal.aborted, 'the older run was stopped');
    s.runner.finish(result([diag(2, 'new')]), 1);
    await settle();
    assert.equal(s.host.shown.get('/p/main.loom')![0].message, 'new');
    assert.equal(s.host.sets.length, 1, 'the stopped run showed nothing');
});

test('two files are checked independently', async () => {
    const s = setup();
    s.controller.request('/p/a.loom');
    s.controller.request('/p/b.loom');
    s.scheduler.advance(CHECK_DEBOUNCE_MS);
    assert.deepEqual(s.runner.requests.map((r) => r.file).sort(), ['/p/a.loom', '/p/b.loom']);
    assert.ok(s.runner.requests.every((r) => !r.signal.aborted));
});

test('only the problems of the file asked about are shown on it', async () => {
    const s = setup();
    s.controller.request('/p/main.loom');
    s.scheduler.advance(CHECK_DEBOUNCE_MS);
    s.runner.finish(result([diag(1, 'mine'), diag(2, 'from an import', 'error', '/p/lib.loom')]));
    await settle();

    assert.deepEqual(s.host.shown.get('/p/main.loom')!.map((d) => d.message), ['mine']);
});

test('closing a file clears its problems and stops its run and its pending check', async () => {
    const s = setup();
    s.controller.request('/p/main.loom');
    s.scheduler.advance(CHECK_DEBOUNCE_MS);
    s.controller.closed('/p/main.loom');
    assert.ok(s.runner.requests[0].signal.aborted);
    s.controller.request('/p/main.loom');
    s.controller.closed('/p/main.loom');
    assert.equal(s.scheduler.waiting, 0);
    assert.equal(s.host.shown.has('/p/main.loom'), false);
});

test('a check that cannot run leaves what was shown, and says why once per kind of failure', async () => {
    const s = setup();
    s.controller.request('/p/main.loom');
    s.scheduler.advance(CHECK_DEBOUNCE_MS);
    s.runner.finish(result([diag(1, 'kept')]));
    await settle();

    for (let i = 0; i < 3; i++) {
        s.controller.request('/p/main.loom');
        s.scheduler.advance(CHECK_DEBOUNCE_MS);
        s.runner.fail(new RuntimeMissingError('java', 'Java was not found (java).'), i + 1);
        await settle();
    }

    assert.equal(s.host.shown.get('/p/main.loom')![0].message, 'kept');
    assert.equal(s.host.notices.length, 1);
    assert.match(s.host.notices[0], /Loom could not check main\.loom: Java was not found/);
});

test('output that is not a check result is an error to the person once, and shows nothing', async () => {
    const s = setup();
    s.controller.request('/p/main.loom');
    s.scheduler.advance(CHECK_DEBOUNCE_MS);
    s.runner.finish('Error: something printed text');
    await settle();

    assert.equal(s.host.sets.length, 0);
    assert.match(s.host.notices[0], /did not print JSON/);
});

test('after dispose nothing runs and nothing is shown', async () => {
    const s = setup();
    s.controller.request('/p/main.loom');
    s.scheduler.advance(CHECK_DEBOUNCE_MS);
    s.controller.dispose();
    s.runner.finish(result([diag(1, 'late')]));
    await settle();
    s.controller.request('/p/main.loom');
    s.scheduler.advance(CHECK_DEBOUNCE_MS);

    assert.equal(s.host.sets.length, 0);
    assert.equal(s.runner.requests.length, 1);
});
