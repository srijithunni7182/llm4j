/** `weave check --format json` through the extension's own runner and controller, with the real weave.jar and real processes. */
import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { CheckDiagnostic, parseCheck } from '../src/check/model';
import { CHECK_DEBOUNCE_MS, CheckDiagnosticsController, DiagnosticsHost } from '../src/check/controller';
import { WeaveCheckRunner } from '../src/check/runner';
import { WeaveGraphRunner } from '../src/graph/runner';
import { FakeScheduler, settle } from './support';

const child_process = require('child_process');
const extRoot = path.join(__dirname, '..', '..');
const jar = path.join(extRoot, 'bin', 'weave.jar');
const hasJava = child_process.spawnSync('java', ['-version']).status === 0;
const probe = fs.existsSync(jar) && hasJava ? child_process.spawnSync('java', ['-jar', jar, 'check', '--help']) : undefined;
const supportsCheck = probe !== undefined && (probe.stdout.toString() + probe.stderr.toString()).includes('--no-env');
const skip = !hasJava ? 'java is not installed' : !fs.existsSync(jar) ? 'no weave.jar: run scripts/build-vsix.sh' : !supportsCheck ? 'weave.jar is older than weave check --no-env: run scripts/build-vsix.sh' : false;

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-check-'));
function script(name: string, source: string): string {
    const file = path.join(dir, name);
    fs.writeFileSync(file, source);
    return file;
}

const runner = () => new WeaveCheckRunner(new WeaveGraphRunner({ javaPath: 'java', jarPath: jar, timeoutMs: 60000 }));
const check = async (file: string) => parseCheck(await runner().run({ file, signal: new AbortController().signal }));

test('R1.1: a script that is fine has no problems', { skip }, async () => {
    const result = await check(script('fine.loom', 'agent A { model: "ollama/llama3" system: "s" }\nworkflow Main() { delegate "x" to A -> r\n note "{r}" }\n'));
    assert.equal(result.ok, true);
    assert.deepEqual(result.diagnostics, []);
});

test('R1.2: a syntax error comes with its line, and the file is the one asked about', { skip }, async () => {
    const file = script('bad.loom', 'agent A { model: "ollama/llama3" }\nworkflow Main() {\n    delegate "x" to A\n}\n');
    const result = await check(file);
    assert.equal(result.ok, false);
    assert.equal(result.diagnostics[0].severity, 'error');
    assert.ok(result.diagnostics[0].line >= 3, `line ${result.diagnostics[0].line}`);
    assert.equal(fs.realpathSync(result.diagnostics[0].file), fs.realpathSync(file));
});

test('R6.1: a result that is stored and never read is a warning with its line', { skip }, async () => {
    const result = await check(script('unused.loom', 'agent A { model: "ollama/llama3" system: "s" }\nworkflow Main() {\n    delegate "x" to A -> unused\n    note "done"\n}\n'));
    assert.equal(result.ok, true);
    assert.equal(result.diagnostics.length, 1);
    assert.equal(result.diagnostics[0].severity, 'warning');
    assert.equal(result.diagnostics[0].line, 3);
    assert.match(result.diagnostics[0].message, /unused is set here and never used/);
});

test('R6.3: keys that are not set yet are listed, not reported as problems', { skip }, async () => {
    const result = await check(script('keys.loom', 'tool Search { use: serpapi  api_key: env.NOT_SET_ANYWHERE_KEY }\nagent A { model: "ollama/llama3" system: "s" tools: [Search] }\nworkflow Main() { delegate "x" to A -> r\n note "{r}" }\n'));
    assert.equal(result.ok, true);
    assert.deepEqual(result.diagnostics, []);
    assert.deepEqual(result.notSetYet, ['NOT_SET_ANYWHERE_KEY']);
});

test('F1: a percent sign and an apostrophe in a word, which the old editor scanner flagged, are fine', { skip }, async () => {
    const result = await check(script('percent.loom', 'budget { tokens: 400000  calls: 60  warn_at: 80% }\nagent A { model: "ollama/llama3" system: "s" }\nworkflow Main() {\n    // the user doesn\'t see this\n    delegate "x" to A -> r\n    note "{r}"\n}\n'));
    assert.equal(result.ok, true);
    assert.deepEqual(result.diagnostics, []);
});

test('R1.2: the controller shows what the real weave check finds', { skip }, async () => {
    const file = script('shown.loom', 'agent A { model: "ollama/llama3" system: "s" }\nworkflow Main() {\n    delegate "x" to A -> unused\n    note "done"\n}\n');
    const shown = new Map<string, CheckDiagnostic[]>();
    const host: DiagnosticsHost = { set: (f, d) => void shown.set(f, d), clear: (f) => void shown.delete(f), notify: (m) => assert.fail(m) };
    const scheduler = new FakeScheduler();
    const controller = new CheckDiagnosticsController({ runner: runner(), host, scheduler });

    controller.request(file);
    scheduler.advance(CHECK_DEBOUNCE_MS);
    for (let i = 0; i < 100 && !shown.has(file); i++) {
        await new Promise((resolve) => setTimeout(resolve, 100));
        await settle();
    }

    assert.equal(shown.get(file)![0].line, 3);
    assert.match(shown.get(file)![0].message, /never used/);
});
