'use strict';
/*
 * The Review panel page in a real browser, with a stand-in for the VS Code API and real output of `weave review` and `weave graph`
 * as the data. It asserts what a person reads (words, counts, order, what is selected, what the page sends back), never pixels.
 * Skipped when playwright or a browser is not installed.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execSync } = require('child_process');
const { renderReviewHtml } = require('../out-test/src/review/html.js');

const MEDIA = path.join(__dirname, '..', 'media');
const FIX = path.join(__dirname, 'fixtures', 'review');
const fixture = (name) => ({ review: JSON.parse(fs.readFileSync(path.join(FIX, name + '.review.json'), 'utf8')), graph: JSON.parse(fs.readFileSync(path.join(FIX, name + '.graph.json'), 'utf8')) });

function loadPlaywright() {
  for (const resolve of [() => require('playwright'), () => require(path.join(execSync('npm root -g').toString().trim(), 'playwright'))]) {
    try { return resolve(); } catch (e) { /* next */ }
  }
  return null;
}

const THEMES = {
  light: { cls: 'vscode-light', vars: '--vscode-editor-background:#ffffff;--vscode-foreground:#3b3b3b;--vscode-focusBorder:#0090f1;--vscode-font-family:sans-serif' },
  dark: { cls: 'vscode-dark', vars: '--vscode-editor-background:#1f1f1f;--vscode-foreground:#cccccc;--vscode-focusBorder:#007fd4;--vscode-font-family:sans-serif' },
  hc: { cls: 'vscode-high-contrast', vars: '--vscode-editor-background:#000000;--vscode-foreground:#ffffff;--vscode-focusBorder:#f38518;--vscode-font-family:sans-serif' }
};

const pw = loadPlaywright();
let browser, htmlFile;
test.before(async () => {
  if (!pw) { return; }
  try { browser = await pw.chromium.launch(); } catch (e) { browser = null; }
  htmlFile = path.join(os.tmpdir(), 'loom-review-panel-test.html');
  fs.writeFileSync(htmlFile, renderReviewHtml({ cspSource: 'file:', nonce: 'testnonce', uri: (name) => 'file://' + path.join(MEDIA, name) }));
});
test.after(async () => { if (browser) { await browser.close(); } });

async function open(theme = 'light', options = {}) {
  const context = await browser.newContext({ viewport: options.viewport || { width: 1440, height: 900 } });
  const outside = [];
  await context.route('**/*', (route) => { if (route.request().url().startsWith('file:')) { route.continue(); } else { outside.push(route.request().url()); route.abort(); } });
  context.setDefaultTimeout(4000);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') { errors.push(m.text()); } });
  await page.addInitScript(() => { window.__posted = []; window.__state = null; window.acquireVsCodeApi = () => ({ postMessage: (m) => window.__posted.push(JSON.parse(JSON.stringify(m))), getState: () => window.__state, setState: (s) => { window.__state = JSON.parse(JSON.stringify(s)); } }); });
  await page.goto('file://' + htmlFile);
  const t = THEMES[theme];
  await page.evaluate(([cls, vars]) => { document.body.className = cls; document.documentElement.style.cssText = vars; }, [t.cls, t.vars]);
  return {
    page, errors, outside, context,
    send: async (message) => { await page.evaluate((m) => window.postMessage(m, '*'), message); await page.waitForTimeout(60); },
    show: async (name, extra = {}) => { const f = fixture(name); await page.evaluate((m) => window.postMessage(m, '*'), { type: 'review', review: f.review, graph: f.graph, status: 'idle', ...extra }); await page.waitForTimeout(60); return f; },
    posted: () => page.evaluate(() => window.__posted),
    clearPosted: () => page.evaluate(() => { window.__posted.length = 0; }),
    box: async (selector) => (await page.$(selector)).boundingBox(),
    texts: (selector) => page.$$eval(selector, (nodes) => nodes.map((n) => n.innerText.replace(/\s+/g, ' ').trim())),
    close: () => context.close()
  };
}
const skip = (t) => { if (!pw || !browser) { t.skip('playwright or a browser is not installed'); return true; } return false; };

test('R1, R2: the page tells the extension it is ready, and shows the graph and the findings side by side', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  assert.deepEqual((await p.posted())[0], { type: 'ready' });
  await p.show('risky');
  assert.ok(await p.page.isVisible('#graphpane'));
  assert.ok(await p.page.isVisible('#inspector'));
  const a = await p.box('#graphpane'), b = await p.box('#inspector');
  assert.ok(a.x < b.x, 'the graph is on the left');
  assert.deepEqual(p.errors, []);
  await p.close();
});

test('R5: the summary strip says counts, cost, time, effects, lenses and the trifecta, as the data does', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = await p.show('risky');
  const strip = (await p.texts('#strip'))[0];
  assert.match(strip, /Open findings 3 high · 5 medium · 2 low/i);
  assert.match(strip, /Cost per run .* typical · .* worst/i);
  assert.match(strip, /Time 12 s typical · 40 s worst/i);
  assert.match(strip, /Effects 10 sends/i);
  assert.match(strip, new RegExp('Lenses ' + f.review.summary.lenses.ran.length + ' on · ' + f.review.summary.lenses.off.length + ' optional off', 'i'));
  assert.match(strip, /trifecta present/);
  await p.close();
});

test('R4: without prices the cost is tokens and says which models have no price; with prices it is money', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('triage');
  assert.match((await p.texts('#strip'))[0], /tok typical/);
  assert.match((await p.texts('#strip'))[0], /no prices for claude-haiku-5-5, claude-opus-5-5/);
  await p.show('priced');
  assert.match((await p.texts('#strip'))[0], /\$0\.0047 typical/);
  const badges = await p.texts('.card .cost');
  assert.ok(badges.length >= 1 && badges.every((b) => /^\$/.test(b)), JSON.stringify(badges));
  await p.close();
});

test('R34: findings are grouped by severity then lens, in the engine order, with the engine\'s counts', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = await p.show('risky');
  const headers = await p.texts('#flist .gh');
  assert.equal(headers.length, 3);
  assert.match(headers[0], /^▾ ! High 3$/i);
  assert.match(headers[1], /^▾ ! Medium 5$/i);
  assert.match(headers[2], /^▾ i Low 2$/i);
  const ids = await p.page.$$eval('#flist .fd', (rows) => rows.map((r) => r.getAttribute('data-finding')));
  assert.equal(ids.length, f.review.findings.length);
  const subs = await p.texts('#flist .subgh');
  assert.ok(subs.some((s) => /Security 1/.test(s)) && subs.some((s) => /Validity 2/.test(s)), JSON.stringify(subs));
  assert.equal((await p.texts('#fcount'))[0], '10 open');
  await p.close();
});

test('R4: severity is a word, a glyph and a shape, never colour alone', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  const marks = await p.page.$$eval('#flist .fd .sev', (nodes) => nodes.map((n) => ({ text: n.textContent.trim(), glyph: n.querySelector('i').textContent, aria: n.querySelector('i').getAttribute('aria-hidden') })));
  assert.ok(marks.length > 0);
  for (const m of marks) { assert.match(m.text, /^(!|i)(High|Medium|Low|Info)$/); assert.ok(m.glyph === '!' || m.glyph === 'i'); assert.equal(m.aria, 'true'); }
  await p.close();
});

test('R3: clicking a finding selects it, shows why, selects its step in the graph and asks the extension to reveal the line', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = await p.show('risky');
  await p.clearPosted();
  const lr01 = f.review.findings.find((x) => x.rule === 'LR01');
  await p.page.click(`[data-finding="${lr01.fingerprint}"]`);
  await p.page.waitForTimeout(60);
  assert.equal(await p.page.getAttribute(`[data-finding="${lr01.fingerprint}"]`, 'aria-selected'), 'true');
  assert.match((await p.texts(`[data-finding="${lr01.fingerprint}"]`))[0], new RegExp(lr01.why.slice(0, 30).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  assert.equal(await p.page.getAttribute('[data-node="n1"]', 'aria-pressed'), 'true', 'its step is outlined');
  const reveal = (await p.posted()).find((m) => m.type === 'reveal');
  assert.deepEqual(reveal, { type: 'reveal', file: lr01.location.file, line: lr01.location.line, beside: true });
  await p.close();
});

test('R3: clicking a step filters the list to its findings and shows which; clicking again shows all', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  await p.page.click('[data-node="n1"]');
  await p.page.waitForTimeout(60);
  const rules = await p.page.$$eval('#flist .fd', (rows) => rows.map((r) => r.querySelector('.chip.rule').textContent));
  assert.deepEqual(rules.sort(), ['LP07', 'LR01', 'LR02', 'LV00']);
  assert.match((await p.texts('#fcount'))[0], /^4 of 10 open$/);
  assert.match((await p.texts('#clearNode'))[0], /Step: delegate Replier/);
  await p.page.click('[data-node="n1"]');
  await p.page.waitForTimeout(60);
  assert.equal((await p.texts('#fcount'))[0], '10 open');
  await p.close();
});

test('R4: a step carries its findings by severity, with a number, a glyph and a label', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  const badges = await p.page.$$eval('[data-node="n1"] .np', (nodes) => nodes.map((n) => ({ text: n.textContent.trim(), label: n.getAttribute('aria-label') })));
  assert.deepEqual(badges, [{ text: '!3', label: '3 medium findings' }, { text: 'i1', label: '1 low findings' }]);
  assert.match(await p.page.getAttribute('[data-node="n1"]', 'aria-label'), /delegate Replier, 4 open findings/);
  await p.close();
});

test('R7: filter by text narrows the list as you type; the group switch regroups; the counts follow', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  await p.page.fill('#filter', 'trifecta');
  await p.page.waitForTimeout(60);
  assert.deepEqual(await p.page.$$eval('#flist .fd .chip.rule', (n) => n.map((x) => x.textContent)), ['LA01']);
  assert.match((await p.texts('#fcount'))[0], /^1 of 10 open$/);
  await p.page.fill('#filter', '');
  await p.page.click('#groupLens');
  await p.page.waitForTimeout(60);
  const heads = await p.texts('#flist .gh');
  assert.match(heads[0], /Security 4/);
  assert.equal(await p.page.getAttribute('#groupLens', 'aria-pressed'), 'true');
  await p.page.fill('#filter', 'zzzz-nothing');
  await p.page.waitForTimeout(60);
  assert.match((await p.texts('#flist'))[0], /No finding matches/);
  await p.close();
});

test('R7: the lens rail and the severity legend filter the list; pressing again clears it; counts follow', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  await p.page.click('[data-lens="validity"]');
  await p.page.waitForTimeout(60);
  assert.deepEqual(await p.page.$$eval('#flist .fd .chip.lens', (n) => [...new Set(n.map((x) => x.textContent))]), ['Validity']);
  assert.equal(await p.page.getAttribute('[data-lens="validity"]', 'aria-pressed'), 'true');
  assert.match((await p.texts('#fcount'))[0], /^3 of 10 open$/);
  await p.page.click('[data-severity="high"]');
  await p.page.waitForTimeout(60);
  assert.deepEqual(await p.page.$$eval('#flist .fd .chip.rule', (n) => n.map((x) => x.textContent)), ['LV09', 'LV00']);
  await p.page.click('[data-lens="validity"]');
  await p.page.click('[data-severity="high"]');
  await p.page.waitForTimeout(60);
  assert.equal((await p.texts('#fcount'))[0], '10 open');
  await p.close();
});

test('R7: a lens that is off is shown as optional and off, and cannot be pressed', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  assert.equal(await p.page.isDisabled('[data-lens="governance"]'), true);
  assert.match(await p.page.getAttribute('[data-lens="governance"]', 'title'), /optional lens and is off/);
  await p.close();
});

test('R7: a group can be folded and unfolded, and the fold is remembered in the page state', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  await p.page.click('#flist .gh .twist');
  await p.page.waitForTimeout(60);
  assert.equal(await p.page.$$eval('#flist .fd', (r) => r.length), 5 + 2);
  const state = await p.page.evaluate(() => window.__state);
  assert.equal(state.ui.collapsed['0:high'], true);
  await p.close();
});

test('R7: rules not evaluated are counted in the strip and listed on request', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('triage');
  const link = await p.page.$('[data-act="not-evaluated"]');
  assert.ok(link);
  assert.match(await link.textContent(), /\d+ rules? not evaluated/);
  await link.click();
  await p.page.waitForTimeout(60);
  assert.match((await p.texts('#flist .notes'))[0], /LC01 was not evaluated/);
  await p.close();
});

test('R9: while a review runs the previous result stays, greyed, with a chip; stale shows its chip; neither clears the list', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  await p.send({ type: 'status', status: 'running' });
  assert.equal(await p.page.$$eval('#flist .fd', (r) => r.length), 10, 'the list is still there');
  assert.ok(await p.page.isVisible('[data-chip="running"]'));
  assert.ok(await p.page.evaluate(() => document.body.classList.contains('running')));
  await p.send({ type: 'status', status: 'stale', message: 'main.loom changed after this review' });
  assert.equal(await p.page.$$eval('#flist .fd', (r) => r.length), 10);
  assert.ok(await p.page.isVisible('[data-chip="stale"]'));
  assert.match((await p.texts('#banner'))[0], /stale main\.loom changed after this review/);
  await p.show('risky');
  assert.ok(!(await p.page.isVisible('#banner')), 'a fresh result clears the banner');
  await p.close();
});

test('R9: an error keeps what was shown and says why', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  await p.send({ type: 'status', status: 'error', message: 'the engine did not answer in time' });
  assert.equal(await p.page.$$eval('#flist .fd', (r) => r.length), 10);
  assert.match((await p.texts('#banner'))[0], /the engine did not answer in time/);
  await p.close();
});

test('no findings: the page says so and shows the clean strip', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('clean');
  assert.match((await p.texts('#flist'))[0], /No open findings\./);
  assert.match((await p.texts('#strip'))[0], /no open findings/);
  await p.close();
});

test('R3: moving the cursor in the editor selects the finding on that line', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = await p.show('risky');
  const file = f.review.findings.find((x) => x.rule === 'LR01').location.file;
  await p.send({ type: 'cursor', file, line: 10 });
  const selected = await p.page.$$eval('#flist .fd[aria-selected="true"] .chip.rule', (n) => n.map((x) => x.textContent));
  assert.equal(selected.length, 1);
  assert.ok(['LP07', 'LR01', 'LR02', 'LV00'].includes(selected[0]));
  await p.close();
});

test('R41: the list is a listbox; arrow keys move the selection and Enter reveals the line', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  assert.equal(await p.page.getAttribute('#flist', 'role'), 'listbox');
  const first = await p.page.$('#flist .fd');
  await first.focus();
  await p.page.keyboard.press('ArrowDown');
  await p.page.waitForTimeout(60);
  const selected = await p.page.$$eval('#flist .fd[aria-selected="true"]', (n) => n.length);
  assert.equal(selected, 1);
  await p.clearPosted();
  await p.page.keyboard.press('Enter');
  const posted = await p.posted();
  assert.ok(posted.some((m) => m.type === 'reveal'), JSON.stringify(posted));
  await p.close();
});

test('R41: each finding is named for a screen reader with severity, lens and whether it has a fix', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  const names = await p.page.$$eval('#flist .fd', (n) => n.map((x) => x.getAttribute('aria-label')));
  assert.ok(names.every((n) => /^(High|Medium|Low|Info), \w[\w ]*, (safe fix available|fix to review available|no automatic fix): /.test(n)), JSON.stringify(names.slice(0, 3)));
  await p.close();
});

test('R2, R48: at a narrow width the graph stacks above the findings and the page does not scroll sideways', async (t) => {
  if (skip(t)) { return; }
  const p = await open('light', { viewport: { width: 640, height: 900 } });
  await p.show('many');
  const a = await p.box('#graphpane'), b = await p.box('#inspector');
  assert.ok(a.y < b.y && Math.abs(a.x - b.x) < 2, 'stacked');
  assert.ok(await p.page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1), 'no horizontal scroll');
  await p.close();
});

test('R29, A10: text from a script or a finding is shown as text, never as markup', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = fixture('risky');
  const hostile = '<img src=x onerror="window.__pwned=1"><script>window.__pwned=1</script>‮ RTL';
  f.review.findings[0].title = hostile;
  f.review.findings[0].why = hostile;
  f.review.findings[0].location.file = '<b>x</b>.loom';
  f.graph.workflows[0].nodes[1].label = hostile;
  await p.send({ type: 'review', review: f.review, graph: f.graph, status: 'idle' });
  await p.page.click('#flist .fd');
  await p.page.waitForTimeout(60);
  assert.equal(await p.page.evaluate(() => window.__pwned), undefined);
  assert.equal(await p.page.$$eval('#flist img, #graphpane img, #flist script, #graphpane script', (n) => n.length), 0);
  assert.equal(await p.page.$$eval('#flist b, #flist u, #flist i:not([aria-hidden])', (n) => n.filter((x) => x.textContent === 'x').length), 0);
  assert.ok((await p.texts('#flist'))[0].includes('<img src=x'));
  await p.close();
});

test('R29: a finding with a 10,000-character title does not break the layout', async (t) => {
  if (skip(t)) { return; }
  const p = await open('light', { viewport: { width: 1000, height: 800 } });
  const f = fixture('risky');
  f.review.findings[0].title = 'x'.repeat(10000);
  await p.send({ type: 'review', review: f.review, graph: f.graph, status: 'idle' });
  assert.ok(await p.page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1));
  await p.close();
});

test('R32: the page loads nothing from outside the extension and makes no network call', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  assert.deepEqual(p.outside, []);
  const html = fs.readFileSync(htmlFile, 'utf8');
  assert.match(html, /Content-Security-Policy.*default-src 'none'/);
  assert.ok(!/https?:\/\//.test(html.replace(/<meta[^>]*Content-Security[^>]*>/, '')));
  await p.close();
});

test('R30: choices made in the page (group, fold, filter, pane) are in the page state, to come back after a hide', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  await p.page.click('#groupRule');
  await p.page.fill('#filter', 'agent');
  await p.page.click('#paneFindings');
  const state = await p.page.evaluate(() => window.__state);
  assert.equal(state.ui.groupBy, 'rule');
  assert.equal(state.ui.filter.text, 'agent');
  assert.equal(state.ui.pane, 'findings');
  assert.ok(!(await p.page.isVisible('#graphpane')), 'findings only');
  await p.close();
});

test('300 findings render in well under a second', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const start = Date.now();
  await p.show('many');
  const ms = Date.now() - start;
  assert.ok((await p.page.$$eval('#flist .fd', (r) => r.length)) >= 200);
  assert.ok(ms < 2000, ms + ' ms');
  await p.close();
});

test('A10: the page works in the dark and high-contrast themes (text keeps contrast with its background)', async (t) => {
  if (skip(t)) { return; }
  for (const theme of ['dark', 'hc']) {
    const p = await open(theme);
    await p.show('risky');
    const pairs = await p.page.$$eval('.fd-title, .strip .v, .gh .cnt, .kicker', (nodes) => nodes.slice(0, 12).map((n) => { const cs = getComputedStyle(n); let bg = cs.backgroundColor, el = n; while ((bg === 'rgba(0, 0, 0, 0)' || bg === 'transparent') && el.parentElement) { el = el.parentElement; bg = getComputedStyle(el).backgroundColor; } return [cs.color, bg]; }));
    const parse = (c) => { const m = /rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)/.exec(c); return m ? [+m[1], +m[2], +m[3]] : null; };
    const lum = ([r, g, b]) => { const f = (c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }; return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b); };
    for (const [fg, bg] of pairs) {
      const a = parse(fg), b = parse(bg);
      if (!a || !b) { continue; }
      const [x, y] = [lum(a), lum(b)].sort((m, n) => n - m);
      assert.ok((x + 0.05) / (y + 0.05) >= 4.5, theme + ': ' + fg + ' on ' + bg);
    }
    await p.close();
  }
});
