'use strict';
/*
 * A1, A3: the whole path with nothing faked but VS Code itself. The real engine (weave serve) reviews a real script, the controller
 * and source of the extension turn that into the state the panel host sends, and the real page shows it. Skipped when the Loom module
 * is not built or playwright has no browser.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn, execSync } = require('child_process');
const { ProcessEngineClient } = require('../out-test/src/engine/client.js');
const { EngineReviewSource, ProcessReviewSource } = require('../out-test/src/review/source.js');
const { ReviewController } = require('../out-test/src/review/controller.js');
const { TimeoutScheduler } = require('../out-test/src/graph/timeoutScheduler.js');
const { renderReviewHtml } = require('../out-test/src/review/html.js');

const loomModule = path.join(__dirname, '..', '..', 'ai-agent4j-loom');
const classpath = [path.join(loomModule, 'target', 'classes'), path.join(loomModule, 'target', 'lib', '*')].join(path.delimiter);
const built = fs.existsSync(path.join(loomModule, 'target', 'classes', 'io', 'github', 'llm4j', 'loom', 'cli', 'ServeCommand.class')) && fs.existsSync(path.join(loomModule, 'target', 'lib'));
const scripts = path.join(__dirname, 'fixtures', 'review', 'scripts');

function loadPlaywright() {
  for (const resolve of [() => require('playwright'), () => require(path.join(execSync('npm root -g').toString().trim(), 'playwright'))]) {
    try { return resolve(); } catch (e) { /* next */ }
  }
  return null;
}

function startReal() {
  const child = spawn('java', ['-cp', classpath, 'io.github.llm4j.loom.cli.WeaveCLI', 'serve', '--stdio'], { stdio: ['pipe', 'pipe', 'pipe'] });
  return { write: (b) => { child.stdin.write(b); }, onData: (l) => child.stdout.on('data', l), onStderr: (l) => child.stderr.on('data', (c) => l(c.toString())), onExit: (l) => child.once('exit', l), kill: () => child.kill() };
}

test('A1: the real engine\'s review of a real script reaches the page, and the page shows the engine\'s own numbers', { skip: !built && 'ai-agent4j-loom is not built' }, async (t) => {
  const pw = loadPlaywright();
  if (!pw) { return t.skip('playwright is not installed'); }
  let browser;
  try { browser = await pw.chromium.launch(); } catch (e) { return t.skip('no browser'); }
  const engine = new ProcessEngineClient({ start: startReal, roots: () => [scripts], scheduler: new TimeoutScheduler(), requestTimeoutMs: 60000 });
  engine.start();
  try {
    const source = new EngineReviewSource(() => engine, new ProcessReviewSource({}));
    const controller = new ReviewController({ source, scheduler: new TimeoutScheduler(), settings: () => ({ on: 'save' }), unsavedText: () => undefined });
    const file = path.join(scripts, 'risky.loom');
    await controller.request(file);
    const state = controller.state(file);
    assert.equal(state.status, 'idle');
    assert.equal(state.review.findings.length, 10);

    const html = path.join(os.tmpdir(), 'loom-review-e2e.html');
    const media = path.join(__dirname, '..', 'media');
    fs.writeFileSync(html, renderReviewHtml({ cspSource: 'file:', nonce: 'n', uri: (n) => 'file://' + path.join(media, n) }));
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    const page = await context.newPage();
    await page.addInitScript(() => { window.acquireVsCodeApi = () => ({ postMessage() {}, getState: () => null, setState() {} }); });
    await page.goto('file://' + html);
    await page.evaluate((m) => window.postMessage(m, '*'), { type: 'review', review: state.review, graph: state.graph, status: state.status, entry: file });
    await page.waitForTimeout(150);
    const strip = (await page.$eval('#strip', (n) => n.innerText)).replace(/\s+/g, ' ');
    assert.match(strip, /3 high · 5 medium · 2 low/i);
    assert.match(strip, /trifecta present/);
    assert.equal(await page.$$eval('#flist .fd', (r) => r.length), 10);
    assert.ok((await page.$$eval('[data-node] .np', (r) => r.length)) >= 2, 'steps carry badges');
    await context.close();
  } finally {
    engine.dispose();
    await browser.close();
  }
});

test('R19: with no engine at all the same review comes from running weave, with the same findings', { skip: !built && 'ai-agent4j-loom is not built' }, async () => {
  const { WeaveGraphRunner } = require('../out-test/src/graph/runner.js');
  // the process fallback, pointed at the classes instead of a jar
  const runner = new WeaveGraphRunner({ javaPath: 'java', jarPath: 'unused', timeoutMs: 120000 }, (command, args) => {
    const weaveArgs = args.slice(2);
    return spawn(command, ['-cp', classpath, 'io.github.llm4j.loom.cli.WeaveCLI', ...weaveArgs], { stdio: ['ignore', 'pipe', 'pipe'] });
  });
  const viaProcess = await new ProcessReviewSource(runner).review({ file: path.join(scripts, 'risky.loom'), tier: 'full', signal: new AbortController().signal });
  const engine = new ProcessEngineClient({ start: startReal, roots: () => [scripts], scheduler: new TimeoutScheduler(), requestTimeoutMs: 60000 });
  engine.start();
  try {
    const viaEngine = await new EngineReviewSource(() => engine, new ProcessReviewSource(runner)).review({ file: path.join(scripts, 'risky.loom'), tier: 'full', signal: new AbortController().signal });
    assert.deepEqual(viaEngine.review.findings.map((f) => f.fingerprint), viaProcess.review.findings.map((f) => f.fingerprint));
    assert.deepEqual(viaEngine.review.summary, viaProcess.review.summary);
  } finally {
    engine.dispose();
  }
});
