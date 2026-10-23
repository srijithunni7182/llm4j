'use strict';
/*
 * R6, R35 to R39 with the real engine: a fix makes its finding go away, an accept moves it to accepted, a baseline moves the rest to
 * known. The editor is the only fake (it records the changes and keeps the files in memory).
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn } = require('child_process');
const { ProcessEngineClient } = require('../out-test/src/engine/client.js');
const { TimeoutScheduler } = require('../out-test/src/graph/timeoutScheduler.js');
const { TriageActions, DismissStore, actionsFor } = require('../out-test/src/review/triage.js');
const { parseReview } = require('../out-test/src/review/model.js');

const loomModule = path.join(__dirname, '..', '..', 'ai-agent4j-loom');
const classpath = [path.join(loomModule, 'target', 'classes'), path.join(loomModule, 'target', 'lib', '*')].join(path.delimiter);
const built = fs.existsSync(path.join(loomModule, 'target', 'classes', 'io', 'github', 'llm4j', 'loom', 'cli', 'ServeCommand.class')) && fs.existsSync(path.join(loomModule, 'target', 'lib'));

function startReal() {
  const child = spawn('java', ['-cp', classpath, 'io.github.llm4j.loom.cli.WeaveCLI', 'serve', '--stdio'], { stdio: ['pipe', 'pipe', 'pipe'] });
  return { write: (b) => { child.stdin.write(b); }, onData: (l) => child.stdout.on('data', l), onStderr: (l) => child.stderr.on('data', (c) => l(c.toString())), onExit: (l) => child.once('exit', l), kill: () => child.kill() };
}

/** An editor in memory: files are strings; apply refuses text that moved on, as the real one does. */
class MemoryEditor {
  constructor(files) { this.files = new Map(Object.entries(files)); this.applied = []; this.confirmed = []; }
  async text(file) { return this.files.get(file); }
  async apply(changes) { for (const c of changes) { if (c.existed && this.files.get(c.file) !== c.before) { return false; } } for (const c of changes) { this.files.set(c.file, c.after); } this.applied.push(changes); return true; }
  async confirm(title, change) { this.confirmed.push({ title, change }); return true; }
  async undo() {}
}

async function setup(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-triage-'));
  const file = path.join(dir, 'main.loom');
  fs.copyFileSync(path.join(__dirname, 'fixtures', 'review', 'scripts', 'triage.loom'), file);
  const engine = new ProcessEngineClient({ start: startReal, roots: () => [dir], scheduler: new TimeoutScheduler(), requestTimeoutMs: 60000 });
  engine.start();
  const editor = new MemoryEditor({ [file]: fs.readFileSync(file, 'utf8') });
  const store = new Map();
  const triage = new TriageActions(() => engine, editor, new DismissStore({ get: (k) => store.get(k), update: async (k, v) => { store.set(k, v); } }));
  const review = async (text) => parseReview((await engine.request('loom/review', { file, text })).result);
  t.after(() => { engine.dispose(); fs.rmSync(dir, { recursive: true, force: true }); });
  return { dir, file, engine, editor, triage, review };
}

test('a fix chosen with its answers is shown, applied as one change, and the finding is gone from the next review', { skip: !built && 'ai-agent4j-loom is not built' }, async (t) => {
  const s = await setup(t);
  const before = await s.review();
  const lr01 = before.findings.find((f) => f.rule === 'LR01');
  assert.ok(lr01 && actionsFor(lr01).fix === 'review');
  const out = await s.triage.fixOption(s.file, lr01, 'timeout-only', { seconds: '45' });
  assert.equal(out.ok, true, out.message);
  assert.equal(s.editor.confirmed.length, 1, 'shown first');
  assert.equal(s.editor.applied.length, 1, 'one edit');
  const text = s.editor.files.get(s.file);
  assert.match(text, /timeout 45s/);
  const after = await s.review(text);
  assert.ok(!after.findings.some((f) => f.fingerprint === lr01.fingerprint), 'LR01 is gone');
  assert.equal(fs.readFileSync(s.file, 'utf8').includes('timeout 45s'), false, 'the server wrote nothing');
});

test('a fix whose option does not exist is refused with the engine\'s reason and changes nothing', { skip: !built && 'ai-agent4j-loom is not built' }, async (t) => {
  const s = await setup(t);
  const lr01 = (await s.review()).findings.find((f) => f.rule === 'LR01');
  const out = await s.triage.fixOption(s.file, lr01, 'no-such-option', {});
  assert.equal(out.ok, false);
  assert.equal(s.editor.applied.length, 0);
});

test('accepting writes a line into review.properties, and the next review lists the finding as accepted, not open', { skip: !built && 'ai-agent4j-loom is not built' }, async (t) => {
  const s = await setup(t);
  const before = await s.review();
  const lr01 = before.findings.find((f) => f.rule === 'LR01');
  const out = await s.triage.accept(s.file, { ids: [lr01.fingerprint], scope: 'finding', reason: 'the harness retries' });
  assert.equal(out.ok, true, out.message);
  const props = path.join(s.dir, 'review.properties');
  assert.match(s.editor.files.get(props), /^LR01\.accept = finding [0-9a-f]+ "the harness retries"/m);
  fs.writeFileSync(props, s.editor.files.get(props)); // the editor saves the file
  const after = await s.review();
  assert.ok(!after.findings.some((f) => f.fingerprint === lr01.fingerprint), 'no longer open');
  assert.ok(after.summary.accepted >= 1, 'counted as accepted');
});

test('the lethal trifecta is refused in words, and nothing is written', { skip: !built && 'ai-agent4j-loom is not built' }, async (t) => {
  const s = await setup(t);
  fs.writeFileSync(s.file, fs.readFileSync(path.join(__dirname, 'fixtures', 'review', 'scripts', 'risky.loom'), 'utf8'));
  s.editor.files.set(s.file, fs.readFileSync(s.file, 'utf8'));
  const la01 = (await s.review()).findings.find((f) => f.rule === 'LA01');
  const out = await s.triage.accept(s.file, { ids: [la01.fingerprint], scope: 'finding', reason: 'we know' });
  assert.equal(out.ok, false);
  assert.match(out.message, /accepted in the script/);
  assert.equal(s.editor.applied.length, 0);
});

test('the baseline records what is open apart from what is serious, and the next review shows only new findings', { skip: !built && 'ai-agent4j-loom is not built' }, async (t) => {
  const s = await setup(t);
  const before = await s.review();
  const out = await s.triage.baseline(s.file, false);
  assert.equal(out.ok, true, out.message);
  const baseline = path.join(s.dir, 'review.baseline.json');
  fs.writeFileSync(baseline, s.editor.files.get(baseline));
  const after = await s.review();
  const serious = before.findings.filter((f) => f.severity === 'high' || ['security', 'durability', 'privacy'].includes(f.lens));
  assert.equal(after.findings.length, serious.length, 'only the serious ones stay open');
  assert.equal(after.summary.baselined, before.findings.length - serious.length);
});

test('a fix applied to text the person has since changed is refused by the editor, not forced', { skip: !built && 'ai-agent4j-loom is not built' }, async (t) => {
  const s = await setup(t);
  const lr01 = (await s.review()).findings.find((f) => f.rule === 'LR01');
  const realApply = s.editor.apply.bind(s.editor);
  s.editor.apply = async (changes) => { s.editor.files.set(s.file, s.editor.files.get(s.file) + '\n// typed meanwhile\n'); return realApply(changes); };
  const out = await s.triage.fixOne(s.file, lr01);
  assert.equal(out.ok, false);
  assert.equal(out.rerun, true);
  assert.match(s.editor.files.get(s.file), /typed meanwhile/);
  assert.doesNotMatch(s.editor.files.get(s.file), /timeout/);
});
