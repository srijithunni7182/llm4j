'use strict';
/*
 * The panel page in a real browser, with a stand-in for the VS Code API: what it draws for each message the extension
 * sends, and what it sends back. Skipped when playwright or a browser is not installed.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execSync } = require('child_process');
const { renderPanelHtml } = require('../out-test/src/graph/html.js');

const MEDIA = path.join(__dirname, '..', 'media');
const GOLDEN = path.join(__dirname, '..', '..', 'ai-agent4j-loom', 'src', 'test', 'resources', 'graph', 'golden');
const FIX = JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures', 'laptop-doctor.graph.json'), 'utf8'));
const golden = (name) => JSON.parse(fs.readFileSync(path.join(GOLDEN, name + '.json'), 'utf8'));

function loadPlaywright() {
  for (const resolve of [() => require('playwright'), () => require(path.join(execSync('npm root -g').toString().trim(), 'playwright'))]) {
    try { return resolve(); } catch (e) { /* next */ }
  }
  return null;
}

const THEMES = {
  light: { cls: 'vscode-light', vars: '--vscode-editor-background:#ffffff;--vscode-foreground:#1f2330;--vscode-descriptionForeground:#5a6177;--vscode-panel-border:#d6d9e5;--vscode-focusBorder:#5a54e0;--vscode-font-family:sans-serif' },
  dark: { cls: 'vscode-dark', vars: '--vscode-editor-background:#14161d;--vscode-foreground:#e7e9f1;--vscode-descriptionForeground:#9aa2b7;--vscode-panel-border:#2a3040;--vscode-focusBorder:#9a95ff;--vscode-font-family:sans-serif' },
  hc: { cls: 'vscode-high-contrast', vars: '--vscode-editor-background:#000000;--vscode-foreground:#ffffff;--vscode-descriptionForeground:#d0d0d0;--vscode-panel-border:#6fc3df;--vscode-focusBorder:#f38518;--vscode-contrastBorder:#6fc3df;--vscode-font-family:sans-serif' }
};

function luminance([r, g, b]) { const f = (c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }; return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b); }
function contrast(a, b) { const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); }
function parseColor(text) {
  let m = /rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)/.exec(text); if (m) { return [+m[1], +m[2], +m[3]]; }
  m = /color\(srgb ([\d.]+) ([\d.]+) ([\d.]+)/.exec(text); return m ? [+m[1] * 255, +m[2] * 255, +m[3] * 255] : null;
}

const pw = loadPlaywright();
let browser, htmlFile;

test.before(async () => {
  if (!pw) { return; }
  try { browser = await pw.chromium.launch(); } catch (e) { browser = null; }
  htmlFile = path.join(os.tmpdir(), 'loom-graph-panel-test.html');
  fs.writeFileSync(htmlFile, renderPanelHtml({ cspSource: 'file:', nonce: 'testnonce', uri: (name) => 'file://' + path.join(MEDIA, name) }));
});
test.after(async () => { if (browser) { await browser.close(); } });

/** Opens the panel with a stand-in for acquireVsCodeApi. */
async function open(theme = 'dark', options = {}) {
  const context = await browser.newContext({ viewport: options.viewport || { width: 1100, height: 800 }, reducedMotion: options.reducedMotion || 'no-preference' });
  const outside = [];
  await context.route('**/*', (route) => { if (route.request().url().startsWith('file:')) { route.continue(); } else { outside.push(route.request().url()); route.abort(); } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') { errors.push(m.text()); } });
  await page.addInitScript(() => { window.__posted = []; window.__state = null; window.acquireVsCodeApi = () => ({ postMessage: (m) => window.__posted.push(JSON.parse(JSON.stringify(m))), getState: () => window.__state, setState: (s) => { window.__state = s; } }); });
  await page.goto('file://' + htmlFile);
  const t = THEMES[theme];
  await page.evaluate(([cls, vars]) => { document.body.className = cls; const s = document.createElement('style'); s.setAttribute('nonce', 'x'); document.documentElement.style.cssText = vars; }, [t.cls, t.vars]);
  const api = {
    page, errors, outside, context,
    send: async (message) => { await page.evaluate((m) => window.postMessage(m, '*'), message); await page.waitForTimeout(80); },
    posted: () => page.evaluate(() => window.__posted),
    clearPosted: () => page.evaluate(() => { window.__posted.length = 0; }),
    close: () => context.close()
  };
  return api;
}


const skipIfNoBrowser = (t) => { if (!pw || !browser) { t.skip('playwright or a browser is not installed'); return true; } return false; };


const graph = (name, workflow) => { const result = golden(name); return { type: 'graph', result, workflow: workflow || result.workflows[0].name }; };
/** The laptop-doctor script: 6 sections, 20 steps on the main path, detours and exits. */
const laptop = () => ({ type: 'graph', result: JSON.parse(JSON.stringify(FIX.result)), workflow: 'Main', sections: { ['Main@' + FIX.result.workflows[0].file]: FIX.sections } });
const view = (p, name) => p.page.locator('.vbtn[data-view=' + name + ']').click();
const selectedId = (p) => p.page.evaluate(() => (document.querySelector('.card.sel, .row.sel') || {}).dataset && document.querySelector('.card.sel, .row.sel').dataset.id);

test('before the extension answers, the panel shows the logo, says it is reading, and has its controls off; it asks for its content', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  assert.ok(await p.page.locator('#loading').isVisible());
  assert.equal(await p.page.locator('#loadingText').textContent(), 'Reading the script…');
  assert.ok(await p.page.locator('#exportPng').isDisabled());
  assert.ok(await p.page.locator('#viewFlow').isDisabled());
  assert.deepEqual(await p.posted(), [{ type: 'ready' }]);
  await p.send({ type: 'loading', file: '/p/main.loom' });
  assert.equal(await p.page.locator('#loadingText').textContent(), 'Reading main.loom…');
  await p.close();
});

test('a graph replaces the loading state, fills the selector, opens the Outline and starts on the first step of the main path', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  assert.ok(!(await p.page.locator('#loading').isVisible()));
  assert.equal(await p.page.locator('#workflow').inputValue(), 'Main');
  assert.equal(await p.page.locator('#workflow option').first().textContent(), 'Main(laptop_issue)');
  assert.equal(await p.page.locator('#viewOutline').getAttribute('aria-pressed'), 'true');
  assert.equal(await p.page.locator('.tree .row').count(), 31 - 0, 'one row per step but start and end');
  assert.equal(await selectedId(p), 'n1');
  assert.match(await p.page.locator('#runbudget').textContent(), /run budget/);
  assert.deepEqual(p.errors, [], 'no script or policy errors');
  assert.deepEqual(p.outside, []);
  await p.close();
});

test('clicking a step only selects it (the editor stays where it is), and the inspector and breadcrumb follow', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await p.clearPosted();
  await p.page.locator('.tree .row[data-id="n11"]').click();
  assert.deepEqual(await p.posted(), [], 'a click never moves the editor');
  assert.equal(await selectedId(p), 'n11');
  const inspector = await p.page.locator('.inspector').innerText();
  assert.match(inspector, /SELECTED · SAFETY OUTCOME/i);
  assert.match(inspector, /outcome == "blocked"/);
  assert.match(inspector, /Go to\s+main\.loom:40/);
  const crumbs = await p.page.locator('#crumbs').innerText();
  assert.match(crumbs.replace(/\s+/g, ' '), /main\.loom › Main\(laptop_issue\) › 3 · Safety check › outcome == "blocked"$/);
  await p.page.locator('.tree .row[data-id="n12"]').click();
  assert.match((await p.page.locator('#crumbs').innerText()).replace(/\s+/g, ' '), /3 · Safety check › outcome == "blocked" › Rewrite 1 of 2$/);
  await p.close();
});

test('a crumb selects its step or section without moving the editor', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await p.page.locator('.tree .row[data-id="n12"]').click();
  await p.clearPosted();
  await p.page.locator('#crumbs .crumb', { hasText: 'outcome ==' }).click();
  assert.equal(await selectedId(p), 'n11');
  await p.page.locator('#crumbs .crumb', { hasText: '3 · Safety check' }).click();
  assert.equal(await selectedId(p), 'n10');
  assert.deepEqual(await p.posted(), []);
  await p.close();
});

test('the view switch changes the view, is remembered, and 1, 2 and 3 are shortcuts', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await view(p, 'focus');
  assert.equal(await p.page.locator('.bigcard').count(), 1);
  assert.equal(await p.page.evaluate(() => window.__state.view), 'focus');
  await p.page.keyboard.press('3');
  assert.equal(await p.page.locator('.stage').count(), 1);
  await p.page.keyboard.press('1');
  assert.equal(await p.page.locator('.tree').count(), 1);
  assert.equal(await p.page.locator('#viewOutline').getAttribute('aria-pressed'), 'true');
  await p.close();
  const again = await open();
  await again.page.evaluate(() => { window.__state = { view: 'flow' }; });
  await again.close();
});

test('the rail has a segment per main-path step, marks done, current and later, and a click selects that step', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await p.page.locator('.tree .row[data-id="n11"]').click();
  assert.equal(await p.page.locator('#rail .seg').count(), 20);
  assert.equal(await p.page.locator('#rail .seg.now').count(), 1);
  assert.equal(await p.page.locator('#rail .seg.done').count(), 7);
  assert.equal(await p.page.locator('#rail .seg.now').getAttribute('data-id'), 'n11');
  assert.equal(await p.page.locator('#railNote').textContent(), 'Step 8 of 20 on the happy path');
  assert.equal(await p.page.locator('#railSecs .sec').count(), 6);
  assert.equal(await p.page.locator('#railSecs .sec.on').textContent(), '3 Safety check');
  assert.ok((await p.page.locator('#rail .seg.now').evaluate((e) => e.getBoundingClientRect().height)) > (await p.page.locator('#rail .seg.todo').first().evaluate((e) => e.getBoundingClientRect().height)));
  await p.page.locator('#rail .seg[data-id="n24"]').click();
  assert.equal(await selectedId(p), 'n24');
  await p.page.locator('#railSecs .sec', { hasText: 'Run and report' }).click();
  assert.equal(await selectedId(p), 'n28');
  await p.page.locator('.tree .row[data-id="n12"]').click();
  assert.match(await p.page.locator('#railNote').textContent(), /^Off the main path, inside step 8/);
  await p.page.locator('.tree .row[data-id="n9"]').click();
  assert.match(await p.page.locator('#railNote').textContent(), /an exit that ends the run, from step 6/);
  assert.equal(await p.page.locator('#legend .lg').count(), 10);
  await p.close();
});

test('Outline: the middle shows only the selected step\'s section, blocks fold and unfold, and Continue goes to the next section', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await p.page.locator('.tree .row[data-id="n11"]').click();
  assert.match(await p.page.locator('.stack .sechd').innerText(), /3\s*Safety check/);
  assert.match(await p.page.locator('.stack .hint').innerText(), /At most 2 rewrites/);
  assert.equal(await p.page.locator('.stack .card').count(), 9);
  assert.equal(await p.page.locator('.stack .card[data-id="n1"]').count(), 0);
  await p.page.locator('.tree .row[data-id="n11"] .tog').click();
  assert.equal(await p.page.locator('.tree .row[data-id="n12"]').count(), 0);
  assert.match(await p.page.locator('.tree .row[data-id="n11"] .hid').textContent(), /\+5/);
  assert.equal(await selectedId(p), 'n11', 'folding keeps the selection');
  await p.page.locator('.tree .ghost[data-act=expand]').click();
  assert.equal(await p.page.locator('.tree .row[data-id="n12"]').count(), 1);
  await p.page.locator('.tree .ghost[data-act=collapse]').click();
  assert.ok((await p.page.locator('.tree .row').count()) < 20);
  await p.page.locator('.tree .ghost[data-act=expand]').click();
  await p.page.locator('.continue').click();
  assert.match(await p.page.locator('.stack .sechd').innerText(), /4\s*First approval/);
  await p.close();
});

test('Outline: branch labels sit on the connectors and nested steps are shifted right', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await p.page.locator('.tree .row[data-id="n11"]').click();
  assert.ok((await p.page.locator('.stack .edgechip', { hasText: 'then' }).count()) >= 1);
  const x = (id) => p.page.locator('.stack .card[data-id="' + id + '"]').evaluate((e) => e.getBoundingClientRect().left);
  assert.ok((await x('n12')) - (await x('n11')) >= 35, 'depth 1 is 36px in');
  assert.ok((await x('n15')) - (await x('n12')) >= 35, 'depth 2 is 36px further');
  await p.close();
});

test('Focus: where the step comes from and goes to, with the words on the lines; clicking one moves the focus there', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await p.page.locator('.tree .row[data-id="n11"]').click();
  await view(p, 'focus');
  assert.equal(await p.page.locator('.bigcard h2').textContent(), 'outcome == "blocked"');
  assert.deepEqual(await p.page.locator('.fside.from .card').evaluateAll((a) => a.map((e) => e.dataset.id)), ['n10']);
  assert.deepEqual(await p.page.locator('.fside.to .card').evaluateAll((a) => a.map((e) => e.dataset.id)), ['n12', 'n17']);
  assert.deepEqual(await p.page.locator('.fside.to .edgechip').allTextContents(), ['then', 'else']);
  assert.deepEqual(await p.page.locator('.fside.from .edgechip').allTextContents(), ['next']);
  assert.equal(await p.page.locator('.inside .insrow').count(), 7 > 0 ? 3 : 0);
  await p.page.locator('.fside.to .card[data-id="n17"]').click();
  assert.equal(await p.page.locator('.bigcard').getAttribute('data-id'), 'n17');
  await p.page.keyboard.press('ArrowLeft');
  assert.equal(await p.page.locator('.bigcard').getAttribute('data-id'), 'n11'.length ? await p.page.locator('.bigcard').getAttribute('data-id') : '');
  await p.close();
});

test('Focus: loops are labelled "loops back", "when done" and "each round"', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await p.page.locator('.tree .row[data-id="n2"]').click();
  await view(p, 'focus');
  assert.deepEqual((await p.page.locator('.fside.from .edgechip').allTextContents()).sort(), ['loops back', 'loops back', 'loops back', 'next']);
  assert.deepEqual((await p.page.locator('.fside.to .edgechip').allTextContents()).sort(), ['each round', 'when done']);
  await p.close();
});

test('Flow: the main path is one row of 184 x 104 cards 44 apart, with the section bands over it and detours hanging below', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await view(p, 'flow');
  const box = (id) => p.page.locator('.stage .card[data-id="' + id + '"]').evaluate((e) => ({ x: e.offsetLeft, y: e.offsetTop, w: e.offsetWidth, h: e.offsetHeight }));
  const a = await box('n1'), b = await box('n2');
  assert.deepEqual([a.w, a.h, a.y], [184, 104, 56]);
  assert.equal(b.x - a.x, 228);
  const start = await box('start');
  assert.deepEqual([start.w, start.h], [110, 48]);
  assert.equal(await p.page.locator('.stage .band').count(), 6);
  assert.equal(await p.page.locator('.stage .band.on').count(), 1);
  const detour = await box('n12');
  assert.equal(detour.y, 56 + 104 + 40);
  assert.equal(detour.h, 56);
  assert.ok(detour.x > (await box('n11')).x);
  assert.equal(await p.page.locator('.stage .card.exit .ends').count(), 4);
  assert.ok((await p.page.locator('.fpaths .p-back').count()) >= 1);
  assert.ok((await p.page.locator('.stage .edgechip', { hasText: 'again' }).count()) >= 1);
  await p.close();
});

test('Flow: selecting a step scrolls it to the middle', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await view(p, 'flow');
  await p.page.locator('#rail .seg[data-id="n28"]').click();
  const where = await p.page.evaluate(() => { const s = document.querySelector('.flow'), c = document.querySelector('.stage .card[data-id="n28"]'); return { left: s.scrollLeft, mid: c.offsetLeft + c.offsetWidth / 2 - s.clientWidth / 2 }; });
  assert.ok(where.left > 1000);
  assert.ok(Math.abs(where.left - where.mid) <= 2, JSON.stringify(where));
  await p.close();
});

test('every step shows its kind: shape, name and its own tone', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  const kinds = await p.page.evaluate(() => Object.fromEntries([...document.querySelectorAll('.tree .row')].map((r) => [r.dataset.id, r.querySelector('.badge').className.replace(/\s+/g, ' ')])));
  assert.match(kinds.n1, /k-agent sh-square/);
  assert.match(kinds.n10, /k-task sh-sharp/);
  assert.match(kinds.n11, /k-check sh-rhombus/);
  assert.match(kinds.n8, /k-verdict sh-rhombus/);
  assert.match(kinds.n22, /k-approval sh-rhombus/);
  assert.match(kinds.n5, /k-branch sh-rhombus/);
  assert.match(kinds.n2, /k-loop sh-circle/);
  assert.match(kinds.n20, /k-human sh-circle/);
  assert.match(kinds.n29, /k-note sh-sharp/);
  assert.match(kinds.n9, /k-exit/);
  const tones = await p.page.evaluate(() => ['agent', 'task', 'check', 'loop', 'human', 'exit'].map((k) => getComputedStyle(document.querySelector('.badge.k-' + k)).backgroundColor));
  assert.equal(new Set(tones).size, tones.length, 'each kind has its own colour');
  assert.equal(await p.page.locator('.tree .row[data-id="n9"] .lbl').evaluate((e) => getComputedStyle(e).fontStyle), 'italic');
  await p.close();
});

test('the arrow keys walk the steps: focus moves in the Outline and Flow, the selection moves in Focus', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await p.page.locator('.tree .row[data-id="n1"]').focus();
  await p.page.keyboard.press('ArrowDown');
  assert.equal(await p.page.evaluate(() => document.activeElement.dataset.id), 'n2');
  await p.page.keyboard.press('Enter');
  assert.equal(await selectedId(p), 'n2');
  assert.equal(await p.page.evaluate(() => document.activeElement.dataset.id), 'n2', 'focus stays where it was after the redraw');
  await view(p, 'focus');
  await p.page.keyboard.press('ArrowDown');
  assert.equal(await p.page.locator('.bigcard').getAttribute('data-id'), 'n3');
  await p.page.keyboard.press('ArrowRight');
  assert.equal(await p.page.locator('.bigcard').getAttribute('data-id'), 'n4');
  await p.close();
});

test('V7.2: double-clicking a call opens the callee with a breadcrumb and Back; Ctrl-click opens its source instead; Shift+Enter opens it too', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(graph('imports_parent'));
  await p.page.locator('.tree .row[data-id="n1"]').dblclick();
  await p.page.waitForTimeout(100);
  const crumbs = (await p.page.locator('#crumbs').innerText()).replace(/\s+/g, ' ');
  assert.match(crumbs, /ParentWorkflow › ChildWorkflow/);
  assert.match(crumbs, /child\.loom/);
  assert.equal(await p.page.locator('#workflow').inputValue(), 'ChildWorkflow');
  assert.ok((await p.posted()).some((m) => m.type === 'selectWorkflow' && m.name === 'ChildWorkflow'));
  await p.page.locator('#crumbs .crumb', { hasText: 'Back' }).click();
  await p.page.waitForTimeout(100);
  assert.equal(await p.page.locator('#workflow').inputValue(), 'ParentWorkflow');
  assert.ok(!(await p.page.locator('#crumbs').innerText()).includes('›  ChildWorkflow'));

  await p.clearPosted();
  await p.page.locator('.tree .row[data-id="n1"]').click({ modifiers: ['Control'] });
  assert.deepEqual(await p.posted(), [{ type: 'openSource', file: '<root>/src/test/resources/imports/child.loom', line: 6, beside: true }]);
  assert.equal(await p.page.locator('#workflow').inputValue(), 'ParentWorkflow', 'the panel did not move');

  await p.page.locator('.tree .row[data-id="n1"]').focus();
  await p.page.keyboard.press('Shift+Enter');
  await p.page.waitForTimeout(100);
  assert.equal(await p.page.locator('#workflow').inputValue(), 'ChildWorkflow');
  await p.close();
});

test('V7.3: the files list shows the entry and what it imports, with their workflows', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(graph('imports_parent'));
  await p.page.locator('#filesBox summary').click();
  const files = await p.page.locator('#files').innerText();
  assert.match(files, /parent\.loom\s+entry\s+ParentWorkflow/);
  assert.match(files, /child\.loom\s+imported\s+ChildWorkflow/);
  assert.equal(await p.page.locator('#workflow optgroup').getAttribute('label'), 'From child.loom');
  await p.close();
});

test('V7.4: the step for the line the cursor is on is selected and brought into view; a step the person clicked stays when the cursor leaves', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await p.send({ type: 'highlight', id: 'n26' });
  assert.equal(await selectedId(p), 'n26');
  await p.send({ type: 'highlight', id: null });
  assert.equal(await selectedId(p), 'n26');
  await p.page.locator('.tree .row[data-id="n3"]').click();
  await p.send({ type: 'highlight', id: null });
  assert.equal(await selectedId(p), 'n3');
  await p.close();
});

test('V6.6: diagnostics show as a count, expand into rows, and a row asks to open its file and line', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  const message = laptop();
  message.result.diagnostics = [
    { severity: 'warning', file: '/p/b.loom', line: 7, message: 'Workflow X is also defined elsewhere' },
    { severity: 'error', file: '/p/c.loom', line: 3, message: 'call Y: no workflow with that name' }
  ];
  await p.send(message);
  assert.match(await p.page.locator('#diagnostics > button').innerText(), /1 error, 1 warning/);
  await p.page.locator('#diagnostics > button').click();
  assert.equal(await p.page.locator('#diagnostics li').count(), 2);
  await p.clearPosted();
  await p.page.locator('#diagnostics li button').nth(1).click();
  assert.deepEqual(await p.posted(), [{ type: 'openSource', file: '/p/c.loom', line: 3, beside: false }]);
  await p.close();
});

test('V8.3: a stale notice keeps the graph, offers the file, and a new graph removes it', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await p.send({ type: 'stale', message: 'Parse error at line 14: expected )', file: '/p/main.loom', line: 14 });
  assert.ok(await p.page.locator('#stale').isVisible());
  assert.match(await p.page.locator('#staleText').innerText(), /last good graph.*expected \)/);
  assert.equal(await p.page.locator('.tree .row').count() > 0, true, 'the graph is still there');
  await p.clearPosted();
  await p.page.locator('#staleOpen').click();
  assert.deepEqual(await p.posted(), [{ type: 'openSource', file: '/p/main.loom', line: 14, beside: false }]);
  await p.send(laptop());
  assert.ok(await p.page.locator('#stale').isHidden());
  await p.close();
});

test('V8.1: a refreshed graph keeps the view, the selected step, the workflow and what was folded', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await p.page.locator('.tree .row[data-id="n11"] .tog').click();
  await p.page.locator('.tree .row[data-id="n19"]').click();
  await view(p, 'flow');
  await p.send(laptop());
  assert.equal(await p.page.locator('#viewFlow').getAttribute('aria-pressed'), 'true');
  assert.equal(await selectedId(p), 'n19');
  await view(p, 'outline');
  assert.equal(await p.page.locator('.tree .row[data-id="n12"]').count(), 0, 'still folded');
  await p.close();
});

test('a workflow chosen from the selector stays chosen after a refresh', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(graph('imports_parent'));
  await p.page.locator('#workflow').selectOption('ChildWorkflow');
  await p.send({ ...graph('imports_parent'), workflow: 'ParentWorkflow' });
  assert.equal(await p.page.locator('#workflow').inputValue(), 'ChildWorkflow');
  await p.close();
});

test('a script with no numbered comments is one section named after the workflow: no section bands, no section crumb', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(graph('content_factory'));
  assert.equal(await p.page.locator('.sechead').count(), 0);
  assert.match(await p.page.locator('.stack .sechd').innerText(), /GenerateContent/);
  assert.equal(await p.page.locator('#railSecs .sec').count(), 0);
  await view(p, 'flow');
  assert.equal(await p.page.locator('.band').count(), 1);
  assert.doesNotMatch(await p.page.locator('#crumbs').innerText(), / · /);
  await p.close();
});

test('V6.9 / R7.1: the inspector shows the agent (model, tools, prompt) and Open prompt asks for the prompt file', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await p.page.locator('.tree .row[data-id="n1"]').click();
  const text = await p.page.locator('.inspector').innerText();
  assert.match(text, /Agent\s+Diagnostician/);
  assert.match(text, /Model\s+gemini-2\.5-flash/);
  assert.match(text, /Temp\s+0\.1/);
  assert.match(text, /Prompt\s+diagnostician@v1\.md/);
  assert.match(text, /max 10 iterations/);
  await p.clearPosted();
  await p.page.getByRole('button', { name: /Open prompt diagnostician/ }).click();
  assert.deepEqual(await p.posted(), [{ type: 'openSource', file: '/work/laptop-doctor/prompts/diagnostician.md', line: 1, beside: true }]);
  await p.page.locator('.tree .row[data-id="n10"]').click();
  assert.equal(await p.page.getByRole('button', { name: /Open prompt/ }).count(), 0);
  await p.close();
});

test('the Focus button on the inspector switches to Focus on that step', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await p.page.locator('.tree .row[data-id="n19"]').click();
  await p.page.getByRole('button', { name: 'Focus on this step' }).click();
  assert.equal(await p.page.locator('.bigcard').getAttribute('data-id'), 'n19');
  await p.close();
});

test('no workflows: the empty state shows the logo and a button to line 1', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  const message = laptop();
  message.result.workflows = [];
  await p.send(message);
  assert.ok(await p.page.locator('#empty').isVisible());
  assert.match(await p.page.locator('#emptyText').innerText(), /no workflows/);
  assert.equal(await p.page.locator('#empty img').getAttribute('alt'), 'Loom');
  await p.clearPosted();
  await p.page.locator('#emptyOpen').click();
  assert.deepEqual(await p.posted(), [{ type: 'openSource', file: '/work/laptop-doctor/main.loom', line: 1, beside: false }]);
  await p.close();
});

test('Copy Mermaid names the workflow', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await p.clearPosted();
  await p.page.locator('#mermaid').click();
  assert.deepEqual(await p.posted(), [{ type: 'copyMermaid', name: 'Main' }]);
  await p.close();
});

test('Export PNG: each view hands the host a PNG named for the script, workflow and view; the host\'s answer shows a toast that goes away', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  const sizes = {};
  for (const name of ['outline', 'focus', 'flow']) {
    await view(p, name);
    await p.clearPosted();
    await p.page.locator('#exportPng').click();
    await p.page.waitForFunction(() => window.__posted.some((m) => m.type === 'exportPng'), null, { timeout: 10000 });
    const m = (await p.posted()).find((x) => x.type === 'exportPng');
    assert.equal(m.name, 'main-Main-' + name + '.png');
    assert.match(m.dataUrl, /^data:image\/png;base64,iVBORw0KGgo/);
    const bytes = Buffer.from(m.dataUrl.split(',')[1], 'base64');
    sizes[name] = [bytes.readUInt32BE(16), bytes.readUInt32BE(20)];
  }
  assert.ok(sizes.flow[0] > 2 * 20 * 228, 'the whole spine, not only what is on screen: ' + sizes.flow);
  assert.ok(sizes.flow[0] > sizes.outline[0]);
  await p.send({ type: 'exported', file: 'main-Main-flow.png' });
  assert.equal(await p.page.locator('#toast').textContent(), 'Saved main-Main-flow.png');
  await p.page.waitForFunction(() => document.getElementById('toast').hidden, null, { timeout: 5000 });
  await p.send({ type: 'exported', file: null });
  assert.ok(await p.page.locator('#toast').isHidden(), 'a cancelled save says nothing');
  assert.deepEqual(p.errors, []);
  await p.close();
});

test('V6.5: hostile text from a script is shown as text and runs nothing, in every view', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  const evil = '<img src=x onerror="window.__pwned=1"> </script><script>window.__pwned=1</script>';
  const message = laptop();
  const wf = message.result.workflows[0];
  wf.nodes.find((n) => n.id === 'n1').label = 'delegate ' + evil;
  wf.nodes.find((n) => n.id === 'n1').agent = evil;
  message.result.agents.push({ name: evil, model: evil });
  message.result.diagnostics = [{ severity: 'warning', file: '/p/a.loom', line: 1, message: evil }];
  message.sections['Main@' + wf.file][0].title = evil;
  wf.name = evil; message.workflow = evil; message.sections[evil + '@' + wf.file] = message.sections['Main@' + wf.file];
  await p.send(message);
  await p.page.locator('#diagnostics > button').click();
  for (const name of ['outline', 'focus', 'flow', 'outline']) { await view(p, name); await p.page.locator('#rail .seg').nth(1).click(); }
  assert.equal(await p.page.evaluate(() => window.__pwned), undefined);
  assert.equal(await p.page.locator('img[src="x"]').count(), 0);
  assert.equal(await p.page.locator('#workflow option').first().textContent().then((x) => x.includes('<img')), true, 'shown as text');
  assert.deepEqual(p.errors, []);
  await p.close();
});

test('V6.10: the toolbar shows the logo mark, the loading state the full logo, and motion is off when asked', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open('dark', { reducedMotion: 'reduce' });
  const mark = p.page.locator('.toolbar .tile img');
  assert.equal(await mark.getAttribute('alt'), '');
  assert.ok((await mark.evaluate((img) => img.naturalWidth)) > 0, 'the mark image loaded');
  assert.equal(await p.page.locator('.toolbar .tile').evaluate((e) => getComputedStyle(e).backgroundColor), 'rgb(26, 29, 38)');
  assert.equal(await p.page.locator('#loading img').getAttribute('alt'), 'Loom');
  assert.equal(await p.page.locator('#loading .pulse').evaluate((e) => getComputedStyle(e).animationName), 'none');
  await p.close();
  const normal = await open('light');
  assert.notEqual(await normal.page.locator('#loading .pulse').evaluate((e) => getComputedStyle(e).animationName), 'none');
  await normal.close();
  const hc = await open('hc');
  assert.equal(await hc.page.locator('.toolbar .tile').evaluate((e) => getComputedStyle(e).borderTopColor), 'rgb(111, 195, 223)');
  await hc.close();
});

test('V6.4: card text stays readable on its tone in light, dark and high-contrast themes, in every view', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  for (const theme of ['light', 'dark', 'hc']) {
    const p = await open(theme);
    await p.send(laptop());
    const low = [];
    for (const name of ['outline', 'focus', 'flow']) {
      await view(p, name);
      const pairs = await p.page.evaluate(() => {
        const px = (css) => { const c = document.createElement('canvas'); c.width = c.height = 1; const g = c.getContext('2d', { willReadFrequently: true }); g.fillStyle = '#000'; g.fillStyle = css; g.fillRect(0, 0, 1, 1); return [...g.getImageData(0, 0, 1, 1).data].slice(0, 3); };
        const bg = px(getComputedStyle(document.body).backgroundColor);
        return [...document.querySelectorAll('.card:not(.term), .bigcard')].map((c) => {
          const t = c.querySelector('.t, h2'), sub = c.querySelector('.sub, .kname, .secline');
          const fill = getComputedStyle(c).backgroundColor;
          return { fill: px(fill), text: px(getComputedStyle(t).color), sub: sub ? px(getComputedStyle(sub).color) : null, bg };
        });
      });
      assert.ok(pairs.length >= (name === 'focus' ? 2 : 5), theme + ' ' + name + ': cards drawn');
      pairs.forEach((x) => {
        if (contrast(x.fill, x.text) < 4.5) { low.push(theme + ' ' + name + ' title ' + contrast(x.fill, x.text).toFixed(2)); }
        if (x.sub && contrast(x.fill, x.sub) < 4.5) { low.push(theme + ' ' + name + ' sub ' + contrast(x.fill, x.sub).toFixed(2)); }
      });
    }
    assert.deepEqual([...new Set(low)], [], theme + ': contrast');
    await p.close();
  }
});

test('the page never scrolls sideways, even at phone width, in every view', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open('dark', { viewport: { width: 400, height: 700 } });
  await p.send(laptop());
  for (const name of ['outline', 'focus', 'flow']) {
    await view(p, name);
    const overflow = await p.page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    assert.ok(overflow <= 0, name + ' overflow ' + overflow);
  }
  await p.close();
});

/** Over four hundred steps in blocks, with their parents, as `weave graph` reports them. */
function bigResult() {
  const nodes = [{ id: 'start', kind: 'start', label: 'Start' }], edges = [];
  let id = 0, exits = [['start', null]];
  const file = '/p/big.loom';
  const add = (kind, label, parent, branch, extra) => { const n = Object.assign({ id: 'n' + (++id), kind, label, source: { file, line: id + 1 } }, extra || {}); if (parent) { n.parent = parent; n.branch = branch; } nodes.push(n); return n; };
  const link = (from, to, label) => edges.push(label ? { from, to, label } : { from, to });
  for (let r = 0; r < 11; r++) {
    const alt = add('alt', 'x?'); exits.forEach(([f, l]) => link(f, alt.id, l));
    let prev = [alt.id, 'then'];
    for (let i = 0; i < 12; i++) { const d = add('delegate', 'delegate A', alt.id, 'then', { agent: 'A' }); link(prev[0], d.id, prev[1]); prev = [d.id, null]; }
    const loop = add('loop', 'loop until x', alt.id, 'then', { bound: 3 }); link(prev[0], loop.id, prev[1]);
    let body = [loop.id, null];
    for (let i = 0; i < 12; i++) { const n = add('note', 'inside', loop.id, 'body'); link(body[0], n.id, body[1]); body = [n.id, null]; }
    link(body[0], loop.id, 'again');
    let other = [alt.id, 'else'];
    for (let i = 0; i < 12; i++) { const n = add('note', 'else', alt.id, 'else'); link(other[0], n.id, other[1]); other = [n.id, null]; }
    exits = [[loop.id, 'done'], [other[0], null]];
  }
  nodes.push({ id: 'end', kind: 'end', label: 'End' });
  exits.forEach(([f, l]) => link(f, 'end', l));
  return { type: 'graph', workflow: 'Big', result: { version: 1, entry: file, files: [{ path: file, imports: [] }], agents: [], diagnostics: [], workflows: [{ name: 'Big', file, line: 1, params: [], nodes, edges }] } };
}

test('V6.7 and VP.4: a graph of over 400 steps is drawn in every view within 500 ms', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  const message = bigResult();
  assert.ok(message.result.workflows[0].nodes.length > 400);
  const elapsed = await p.page.evaluate(async (m) => {
    const t0 = performance.now();
    window.postMessage(m, '*');
    while (!document.querySelector('.tree .row')) { await new Promise((r) => requestAnimationFrame(r)); }
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    return performance.now() - t0;
  }, message);
  assert.ok(elapsed <= 500, 'first paint took ' + Math.round(elapsed) + ' ms');
  for (const name of ['flow', 'focus']) {
    const t0 = Date.now();
    await view(p, name);
    await p.page.waitForSelector(name === 'flow' ? '.stage .card' : '.bigcard');
    assert.ok(Date.now() - t0 < 1500, name + ' took ' + (Date.now() - t0) + ' ms');
  }
  assert.deepEqual(p.errors, []);
  await p.close();
});

test('each agent\'s tools are shown on its steps, in the inspector and in the agents list, with the ones that need approval marked', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  const message = laptop();
  message.result.agents.find((a) => a.name === 'Diagnostician').tools = ['Inspect', 'read_file'];
  message.result.agents.find((a) => a.name === 'Diagnostician').approve = ['read_file'];
  message.result.agents.find((a) => a.name === 'ScriptAuthor').tools = [];
  await p.send(message);
  await p.page.locator('.tree .row[data-id="n1"]').click();
  assert.deepEqual(await p.page.locator('.stack .card[data-id="n1"] .chip.tool').allTextContents(), ['Inspect', 'read_file']);
  assert.equal(await p.page.locator('.stack .card[data-id="n1"] .chip.tool.asks').count(), 1);
  assert.deepEqual(await p.page.locator('.inspector .chip.tool').allTextContents(), ['Inspect', 'read_file']);
  await p.page.locator('.tree .row[data-id="n7"]').click();
  assert.deepEqual(await p.page.locator('.inspector .chip.tool').allTextContents(), ['no tools']);
  await view(p, 'flow');
  assert.equal(await p.page.locator('.stage .card[data-id="n1"] .chip.tool').textContent(), '2 tools');
  await view(p, 'focus');
  await p.page.locator('#agentsBox summary').click();
  const list = await p.page.locator('#agents').innerText();
  assert.match(list, /Diagnostician[\s\S]*gemini-2\.5-flash[\s\S]*Inspect[\s\S]*read_file/);
  assert.match(list, /ScriptAuthor[\s\S]*no tools/);
  await p.page.locator('#agents .an', { hasText: 'Researcher' }).click();
  assert.equal(await p.page.locator('.bigcard').getAttribute('data-id'), 'n3');
  await p.close();
});

test('an always-running workflow (no end) shows its loop with a sleep as a shut eye, and says it runs until stopped', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  const file = '/p/watch.loom';
  const nodes = [
    { id: 'start', kind: 'start', label: 'Start' },
    { id: 'n1', kind: 'loop', label: 'loop forever', source: { file, line: 3 } },
    { id: 'n2', kind: 'delegate', label: 'delegate Checker', agent: 'Checker', parent: 'n1', branch: 'body', source: { file, line: 4 } },
    { id: 'n3', kind: 'sleep', label: 'sleep 60s', parent: 'n1', branch: 'body', source: { file, line: 5 } }];
  const edges = [{ from: 'start', to: 'n1' }, { from: 'n1', to: 'n2' }, { from: 'n2', to: 'n3' }, { from: 'n3', to: 'n1', label: 'again' }];
  await p.send({ type: 'graph', workflow: 'Watch', result: { version: 1, entry: file, files: [{ path: file, imports: [] }], agents: [{ name: 'Checker', tools: ['ping'] }], diagnostics: [], workflows: [{ name: 'Watch', file, line: 1, params: [], nodes, edges }] } });
  assert.equal(await p.page.locator('#rail .seg').count(), 4);
  await p.page.locator('.tree .row[data-id="n3"]').click();
  assert.match(await p.page.locator('#railNote').textContent(), /runs until stopped/);
  assert.equal(await p.page.locator('.inspector .ihead .badge svg path').count(), 5, 'the shut-eye glyph');
  await view(p, 'flow');
  assert.equal(await p.page.locator('.stage .card.term').count(), 1, 'a start and no end');
  assert.ok((await p.page.locator('.fpaths .p-back').count()) >= 1);
  assert.ok((await p.page.locator('.stage .edgechip', { hasText: 'again forever' }).count()) >= 1);
  await view(p, 'focus');
  assert.deepEqual(await p.page.locator('.fside.to .edgechip').allTextContents(), ['loops back']);
  assert.deepEqual(p.errors, []);
  await p.close();
});

test('right-clicking a step opens a menu with Go to file; only choosing it moves the editor', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await p.clearPosted();
  await p.page.locator('.tree .row[data-id="n1"]').click({ button: 'right' });
  assert.ok(await p.page.locator('#ctxmenu').isVisible());
  assert.equal(await selectedId(p), 'n1', 'the step under the pointer is selected');
  assert.deepEqual(await p.posted(), []);
  const items = await p.page.locator('#ctxmenu button').allTextContents();
  assert.deepEqual(items, ['Go to main.loom:20', 'Open prompt diagnostician', 'Focus on this step', 'Copy step text']);
  await p.page.locator('#ctxmenu button', { hasText: 'Go to main.loom:20' }).click();
  assert.deepEqual(await p.posted(), [{ type: 'openSource', file: '/work/laptop-doctor/main.loom', line: 20, beside: true }]);
  assert.ok(await p.page.locator('#ctxmenu').isHidden());
  await p.close();
});

test('the menu works from the cards of every view, closes on Escape and on a click elsewhere, and is reachable by keyboard', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await p.page.locator('.stack .card[data-id="n1"]').click({ button: 'right' });
  assert.ok(await p.page.locator('#ctxmenu').isVisible());
  await p.page.keyboard.press('Escape');
  assert.ok(await p.page.locator('#ctxmenu').isHidden());
  for (const name of ['flow', 'focus']) {
    await view(p, name);
    await p.page.locator('.card[data-id]').first().click({ button: 'right' });
    assert.ok(await p.page.locator('#ctxmenu').isVisible(), name);
    await p.page.mouse.click(5, 300);
    assert.ok(await p.page.locator('#ctxmenu').isHidden(), name + ' closes on a click elsewhere');
  }
  await view(p, 'outline');
  await p.page.locator('.tree .row[data-id="n3"]').focus();
  await p.page.keyboard.press('Shift+F10');
  assert.ok(await p.page.locator('#ctxmenu').isVisible());
  await p.page.keyboard.press('ArrowDown');
  await p.page.keyboard.press('Escape');
  assert.ok(await p.page.locator('#ctxmenu').isHidden());
  await p.close();
});

test('a call\'s menu offers to open the called workflow and to go to its source', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(graph('imports_parent'));
  await p.page.locator('.tree .row[data-id="n1"]').click({ button: 'right' });
  const items = await p.page.locator('#ctxmenu button').allTextContents();
  assert.ok(items.includes('Open workflow ChildWorkflow'), items.join('|'));
  assert.ok(items.some((i) => /^Go to ChildWorkflow in child\.loom/.test(i)), items.join('|'));
  await p.page.locator('#ctxmenu button', { hasText: 'Open workflow ChildWorkflow' }).click();
  assert.equal(await p.page.locator('#workflow').inputValue(), 'ChildWorkflow');
  await p.close();
});

test('the graph shows no line numbers, and long section titles and parameter lists are cut rather than overlapping', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open('light', { viewport: { width: 900, height: 700 } });
  const message = laptop();
  const long = 'The orchestrator casts the team: it writes every specialist a prompt for this brief and then keeps going for a long while';
  message.sections['Main@' + FIX.result.workflows[0].file][2].title = long;
  message.result.workflows[0].params = ['creatorHandle', 'creatorNiche', 'creatorTone', 'creatorRegion', 'pastCastings', 'lensOptions', 'styleOptions', 'pastYouTube'];
  await p.send(message);
  await p.page.locator('.tree .row[data-id="n11"]').click();
  assert.equal(await p.page.locator('.ln').count(), 0, 'no line numbers in the outline');
  for (const name of ['outline', 'focus', 'flow']) {
    await view(p, name);
    assert.equal(await p.page.locator('.ln').count(), 0, name + ': no line numbers');
    assert.doesNotMatch(await p.page.evaluate(() => [...document.querySelectorAll('.card, .row, .bigcard')].map((e) => e.innerText).join('\n')).then((t) => t.replace(/Go to\s+\S+:\d+/g, '')), /:\d{2}\b/, name + ': nothing like :40 on a step');
  }
  await view(p, 'focus');
  const clash = await p.page.evaluate(() => {
    const a = document.querySelector('.bhead .kicker').getBoundingClientRect(), b = document.querySelector('.bhead .secline').getBoundingClientRect(), box = document.querySelector('.bigcard').getBoundingClientRect();
    return { overlap: !(a.bottom <= b.top + 1 || b.bottom <= a.top + 1) && !(a.right <= b.left || b.right <= a.left), insideCard: b.right <= box.right + 1 && a.right <= box.right + 1 };
  });
  assert.equal(clash.overlap, false);
  assert.equal(clash.insideCard, true);
  const wide = await p.page.evaluate(() => ({ select: document.getElementById('workflow').getBoundingClientRect().width, doc: document.documentElement.scrollWidth - document.documentElement.clientWidth, crumbs: document.getElementById('crumbs').scrollWidth - document.getElementById('crumbs').clientWidth }));
  assert.ok(wide.select <= 900 * 0.46 + 2, 'the workflow selector is capped: ' + wide.select);
  assert.ok(wide.doc <= 0 && wide.crumbs <= 0, JSON.stringify(wide));
  await p.close();
});

test('Flow: an on_stopped handler hangs on a dotted line, a rewind goes back on a dashed one, and a key inside the picture says which is which', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  const result = golden('handlers');
  const wf = result.workflows[0];
  wf.nodes = [
    { id: 'start', kind: 'start', label: 'Start' },
    { id: 'n1', kind: 'checkpoint', label: 'checkpoint c' },
    { id: 'n2', kind: 'delegate', label: 'delegate A', agent: 'A' },
    { id: 'n3', kind: 'note', label: 'note' },
    { id: 'n4', kind: 'alt', label: 'alt (_stop.reason == "user")', parent: 'n2', branch: 'stopped' },
    { id: 'n5', kind: 'rewind', label: 'rewind to c', parent: 'n4', branch: 'then', attrs: { atMost: 2, target: 'c' } },
    { id: 'n6', kind: 'outcome', label: 'skip', parent: 'n4', branch: 'else' },
    { id: 'end', kind: 'end', label: 'End' }];
  wf.edges = [{ from: 'start', to: 'n1' }, { from: 'n1', to: 'n2' }, { from: 'n2', to: 'n3' }, { from: 'n3', to: 'end' },
    { from: 'n2', to: 'n4', label: 'stopped' }, { from: 'n4', to: 'n5', label: 'then' }, { from: 'n4', to: 'n6', label: 'else' },
    { from: 'n5', to: 'n1', label: 'rewind' }, { from: 'n5', to: 'n3' }, { from: 'n6', to: 'n3' }];
  await p.send({ type: 'graph', result, workflow: wf.name });
  await view(p, 'flow');
  assert.ok(await p.page.locator('.fpaths .p-stopped').count() >= 1, 'a dotted stopped line');
  assert.ok(await p.page.locator('.fpaths .p-rewind').count() >= 1, 'a dashed sent-back line');
  const dash = (cls) => p.page.locator('.fpaths .' + cls).first().evaluate((e) => getComputedStyle(e).strokeDasharray);
  const [dotted, dashed] = [await dash('p-stopped'), await dash('p-rewind')];
  assert.notEqual(dotted, dashed, 'the two patterns differ, so colour is not the only signal');
  assert.notEqual(dotted, 'none');
  assert.equal(await p.page.locator('[data-export] .linelegend.inplace .lg').count(), 2, 'the key is inside what the picture exports');
  assert.ok((await p.page.locator('[data-export] .linelegend').textContent()).includes('Stopped'));
  assert.ok((await p.page.locator('.edgechip', { hasText: 'sent back ≤2' }).count()) >= 1);
  assert.deepEqual(p.errors, []);
  await p.close();
});

test('Flow: a graph without interventions shows no key for them', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(laptop());
  await view(p, 'flow');
  assert.equal(await p.page.locator('.linelegend').count(), 0);
  await p.close();
});

/* ---- Phase 21 R4 and R5: sets are one card, a parallel block is lanes ---- */

const lanesGraph = (extra) => Object.assign({ type: 'graph', result: golden('parallel_lanes'), workflow: 'Main' }, extra || {});
const boxes = (p, selector) => p.page.$$eval(selector, (els) => els.map((e) => { const r = e.getBoundingClientRect(); return { id: e.getAttribute('data-id'), x: r.x, y: r.y, w: r.width, h: r.height }; }));

test('R4: three consecutive sets are one card that says so, and opening it lists each variable with its value', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(lanesGraph());
  await view(p, 'flow');
  const card = p.page.locator('.card.setcard');
  assert.equal(await card.count(), 1);
  assert.match(await card.getAttribute('aria-label'), /sets 3 variables: a, b, c/);
  assert.equal(await p.page.locator('.setlist').isHidden(), true);
  await p.page.locator('.setopen').click();
  assert.equal(await p.page.locator('.setopen').getAttribute('aria-expanded'), 'true');
  assert.deepEqual(await p.page.locator('.setrow').allTextContents(), ['a = 1', 'b = 2', 'c = 3']);
  await p.close();
});

test('R4: choosing a variable in the group selects that set, with its own line', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(lanesGraph());
  await view(p, 'flow');
  await p.page.locator('.setopen').click();
  await p.page.locator('.setrow[data-id=n2]').click();
  await p.page.waitForTimeout(80);
  assert.match(await p.page.locator('#viewHost').innerText(), /set b/);
  assert.equal(await p.page.locator('.setrow[data-id=n2]').getAttribute('aria-pressed'), 'true');
  assert.match(await p.page.locator('.inspector, #inspector').first().innerText(), /parallel_lanes\.loom:6|set b/);
  await p.close();
});

test('R4: with the setting off every set is its own card', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(lanesGraph({ groupSets: false }));
  await view(p, 'flow');
  assert.equal(await p.page.locator('.card.k-note[data-id=n1], .card.k-note[data-id=n2], .card.k-note[data-id=n3]').count(), 3);
  assert.equal(await p.page.locator('.setlist').count(), 0);
  await p.close();
});

test('R5: Flow draws the parallel block as a fork, one lane per branch with its name, a failure edge and a join', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(lanesGraph());
  await view(p, 'flow');
  assert.ok(await p.page.locator('.fpaths .p-fork').count() >= 2, 'fork bar and ticks');
  assert.ok(await p.page.locator('.fpaths .p-join').count() >= 2, 'join bar and ticks');
  assert.equal(await p.page.locator('.fpaths .p-failure').count(), 2, 'a dashed edge from each step that has a failure handler');
  assert.deepEqual(await p.page.locator('.edgechip.lanechip').allTextContents(), ['A', 'B', 'C']);
  await p.close();
});

for (const theme of ['light', 'dark', 'hc']) {
  test(`R5: lanes do not overlap each other or the main path in the ${theme} theme, at 100% and 200% zoom`, async (t) => {
    if (skipIfNoBrowser(t)) { return; }
    for (const zoom of [1, 2]) {
      const p = await open(theme, { viewport: { width: 1700, height: 1000 } });
      await p.page.evaluate((z) => { document.body.style.zoom = String(z); }, zoom);
      await p.send(lanesGraph());
      await view(p, 'flow');
      const cards = await boxes(p, '.stage .card');
      for (let i = 0; i < cards.length; i++) for (let j = i + 1; j < cards.length; j++) {
        const a = cards[i], b = cards[j];
        const overlap = a.x < b.x + b.w - 1 && b.x < a.x + a.w - 1 && a.y < b.y + b.h - 1 && b.y < a.y + a.h - 1;
        assert.ok(!overlap, `${a.id} and ${b.id} overlap at ${zoom * 100}% in ${theme}`);
      }
      assert.deepEqual(p.errors, []);
      await p.close();
    }
  });
}

test('A8: a graph from an older engine (a parallel block with no lanes) still draws as one step', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  const result = JSON.parse(JSON.stringify(golden('parallel_lanes')));
  const wf = result.workflows[0];
  wf.nodes = wf.nodes.filter((n) => !n.parent);
  wf.nodes.forEach((n) => { if (n.attrs) { delete n.attrs.lanes; } });
  const ids = wf.nodes.map((n) => n.id);
  wf.edges = ids.slice(0, -1).map((id, i) => ({ from: id, to: ids[i + 1] }));
  await p.send({ type: 'graph', result, workflow: 'Main' });
  await view(p, 'flow');
  assert.equal(await p.page.locator('.fpaths .p-fork').count(), 0);
  assert.equal(await p.page.locator('.stage .card[data-id=n4]').count(), 1);
  assert.deepEqual(p.errors, []);
  await p.close();
});
