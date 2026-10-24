'use strict';
/*
 * R44 to R49 of spec 10 in a real page: the layout switch, the same data in every layout, what survives a switch, the controls shared
 * by all three, the pane toggle only in Split, and the narrow-width behaviour. Real engine output as data.
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
  for (const resolve of [() => require('playwright'), () => require(path.join(execSync('npm root -g').toString().trim(), 'playwright'))]) { try { return resolve(); } catch (e) { /* next */ } }
  return null;
}
const THEMES = { light: { cls: 'vscode-light', vars: '--vscode-editor-background:#ffffff;--vscode-foreground:#3b3b3b;--vscode-font-family:sans-serif' }, dark: { cls: 'vscode-dark', vars: '--vscode-editor-background:#1f1f1f;--vscode-foreground:#cccccc;--vscode-font-family:sans-serif' } };

const pw = loadPlaywright();
let browser, htmlFile;
test.before(async () => {
  if (!pw) { return; }
  try { browser = await pw.chromium.launch(); } catch (e) { browser = null; }
  htmlFile = path.join(os.tmpdir(), 'loom-review-layout-test.html');
  fs.writeFileSync(htmlFile, renderReviewHtml({ cspSource: 'file:', nonce: 'n', uri: (name) => 'file://' + path.join(MEDIA, name) }));
});
test.after(async () => { if (browser) { await browser.close(); } });

async function open(theme = 'light', options = {}) {
  const context = await browser.newContext({ viewport: options.viewport || { width: 1440, height: 900 } });
  context.setDefaultTimeout(4000);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') { errors.push(m.text()); } });
  await page.addInitScript((initial) => { window.__posted = []; window.__state = initial; window.acquireVsCodeApi = () => ({ postMessage: (m) => window.__posted.push(JSON.parse(JSON.stringify(m))), getState: () => window.__state, setState: (s) => { window.__state = JSON.parse(JSON.stringify(s)); } }); }, options.state || null);
  await page.goto('file://' + htmlFile);
  const t = THEMES[theme];
  await page.evaluate(([cls, vars]) => { document.body.className = cls; document.documentElement.style.cssText = vars; }, [t.cls, t.vars]);
  return {
    page, errors, context,
    show: async (name) => { const f = fixture(name); await page.evaluate((m) => window.postMessage(m, '*'), { type: 'review', review: f.review, graph: f.graph, status: 'idle', entry: '/p/' + name + '.loom' }); await page.waitForTimeout(80); return f; },
    layout: async (name) => { await page.click(`[data-layout="${name}"]`); await page.waitForTimeout(60); },
    texts: (selector) => page.$$eval(selector, (nodes) => nodes.map((n) => n.innerText.replace(/\s+/g, ' ').trim())),
    ids: () => page.$$eval('#flist [data-finding]', (rows) => rows.map((r) => r.getAttribute('data-finding'))),
    state: () => page.evaluate(() => window.__state),
    close: () => context.close()
  };
}
const skip = (t) => { if (!pw || !browser) { t.skip('playwright or a browser is not installed'); return true; } return false; };

test('R44: the panel opens in Split; the switch has Split, Outline and List, and says which is on', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  const buttons = await p.page.$$eval('#layoutSwitch .vbtn', (n) => n.map((b) => [b.textContent, b.getAttribute('aria-pressed')]));
  assert.deepEqual(buttons, [['Split', 'true'], ['Outline', 'false'], ['List', 'false']]);
  assert.ok(await p.page.isVisible('#graphpane') && await p.page.isVisible('#inspector'));
  await p.close();
});

test('R44: the last choice is restored from the saved page state', async (t) => {
  if (skip(t)) { return; }
  const p = await open('light', { state: { ui: { layout: 'list' } } });
  await p.show('risky');
  assert.equal(await p.page.getAttribute('#layoutList', 'aria-pressed'), 'true');
  assert.ok(await p.page.isVisible('#table'));
  await p.close();
});

test('R44: the loom.review.layout setting sets the layout of a panel with no saved choice, and never overrides a saved one', async (t) => {
  if (skip(t)) { return; }
  const fresh = await open();
  await fresh.page.evaluate(() => window.postMessage({ type: 'settings', layout: 'outline' }, '*'));
  await fresh.page.waitForTimeout(60);
  assert.equal(await fresh.page.getAttribute('#layoutOutline', 'aria-pressed'), 'true');
  await fresh.close();
  const saved = await open('light', { state: { ui: { layout: 'list' } } });
  await saved.page.evaluate(() => window.postMessage({ type: 'settings', layout: 'outline' }, '*'));
  await saved.page.waitForTimeout(60);
  assert.equal(await saved.page.getAttribute('#layoutList', 'aria-pressed'), 'true');
  await saved.close();
});

test('R44: choosing a layout draws it and is remembered in the state', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  await p.layout('outline');
  assert.ok(await p.page.isVisible('#tree') && await p.page.isVisible('#centre'));
  assert.equal((await p.state()).ui.layout, 'outline');
  await p.layout('list');
  assert.ok(await p.page.isVisible('#table') && await p.page.isVisible('#minimap'));
  assert.equal((await p.state()).ui.layout, 'list');
  assert.deepEqual(p.errors, []);
  await p.close();
});

test('R49: every layout lists the same findings, in the same order, with the same counts', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = await p.show('risky');
  const seen = {};
  for (const layout of ['split', 'outline', 'list']) {
    await p.layout(layout);
    seen[layout] = { ids: await p.ids(), strip: (await p.texts('#strip'))[0], count: (await p.texts('#fcount'))[0] };
  }
  assert.equal(seen.split.ids.length, f.review.findings.length);
  assert.deepEqual(seen.outline.ids, seen.split.ids);
  assert.deepEqual(seen.list.ids, seen.split.ids);
  assert.equal(seen.outline.strip, seen.split.strip);
  assert.equal(seen.list.strip, seen.split.strip);
  assert.equal(seen.list.count, seen.split.count);
  await p.close();
});

test('R49: a filter shows the same findings in every layout', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  await p.page.fill('#filter', 'retry');
  await p.page.waitForTimeout(60);
  const split = await p.ids();
  assert.ok(split.length >= 1 && split.length < 10);
  for (const layout of ['outline', 'list']) { await p.layout(layout); assert.deepEqual(await p.ids(), split, layout); }
  await p.close();
});

test('R45: switching through all three layouts and back changes nothing the person chose', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  // choose things
  await p.page.click('#groupLens');
  await p.page.fill('#filter', 'agent');
  await p.page.click('[data-lens="security"]');
  const target = (await p.ids())[0];
  await p.page.click(`[data-finding="${target}"]`);
  await p.page.waitForTimeout(60);
  const chosen = (s) => JSON.stringify({ ...s.ui, layout: 'x', scroll: 0 });
  const before = await p.state();
  for (const layout of ['outline', 'list', 'split', 'list', 'outline', 'split']) {
    await p.layout(layout);
    assert.equal(chosen(await p.state()), chosen(before), layout);
    assert.equal(await p.page.getAttribute('#groupLens', 'aria-pressed'), 'true', layout + ': group by');
    assert.equal(await p.page.inputValue('#filter'), 'agent', layout + ': filter text');
    assert.equal(await p.page.getAttribute('[data-lens="security"]', 'aria-pressed'), 'true', layout + ': lens filter');
    assert.equal(await p.page.getAttribute(`[data-finding="${target}"]`, 'aria-selected'), 'true', layout + ': selected finding');
  }
  await p.close();
});

test('R45: folded groups stay folded in every layout', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  await p.page.click('#flist .gh .twist');
  await p.page.waitForTimeout(60);
  const folded = await p.page.$$eval('#flist .gh .twist', (n) => n.map((b) => b.getAttribute('aria-expanded')));
  assert.equal(folded[0], 'false');
  const keys = Object.keys((await p.state()).ui.collapsed);
  for (const layout of ['outline', 'list', 'split']) {
    await p.layout(layout);
    assert.deepEqual(await p.page.$$eval('#flist .gh .twist', (n) => n.map((b) => b.getAttribute('aria-expanded'))), folded, layout);
    assert.deepEqual(Object.keys((await p.state()).ui.collapsed), keys, layout);
  }
  await p.close();
});

test('R45: the selected step survives the switch and is the one drawn as selected in each layout', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  await p.page.click('[data-node="n1"]');
  await p.page.waitForTimeout(60);
  await p.layout('outline');
  assert.equal(await p.page.getAttribute('#tree [data-node="n1"]', 'aria-pressed'), 'true');
  assert.match((await p.texts('#centre .sechd'))[0], /delegate Replier/);
  await p.layout('list');
  assert.equal(await p.page.getAttribute('#minimap [data-node="n1"]', 'aria-pressed'), 'true');
  await p.layout('split');
  assert.equal(await p.page.getAttribute('#graphpane [data-node="n1"]', 'aria-pressed'), 'true');
  assert.match((await p.texts('#clearNode'))[0], /Step: delegate Replier/);
  await p.close();
});

test('R45: scrolling in the list is kept when the layout changes', async (t) => {
  if (skip(t)) { return; }
  const p = await open('light', { viewport: { width: 1440, height: 600 } });
  await p.show('many');
  await p.layout('list');
  await p.page.$eval('#flist', (n) => { n.scrollTop = 600; n.dispatchEvent(new Event('scroll')); });
  await p.page.waitForTimeout(60);
  const at = await p.page.$eval('#flist', (n) => n.scrollTop);
  assert.ok(at >= 300, 'it scrolled');
  await p.layout('split');
  const after = await p.page.$eval('#flist', (n) => n.scrollTop);
  assert.ok(Math.abs(after - at) < 40, `split ${after} vs list ${at}`);
  await p.close();
});

test('R46: the controls are in every layout, once: re-run, group by, filter, and the same summary strip', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  for (const layout of ['split', 'outline', 'list']) {
    await p.layout(layout);
    for (const id of ['#rerun', '#filter', '#groupSeverity', '#groupLens', '#groupRule', '#groupFile', '#strip', '#workflow']) {
      assert.equal(await p.page.$$eval(id, (n) => n.length), 1, `${layout}: ${id}`);
      assert.ok(await p.page.isVisible(id), `${layout}: ${id} visible`);
    }
  }
  await p.close();
});

test('R46: re-run sends the same message from every layout', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  for (const layout of ['split', 'outline', 'list']) {
    await p.layout(layout);
    await p.page.evaluate(() => { window.__posted.length = 0; });
    await p.page.click('#rerun');
    assert.deepEqual(await p.page.evaluate(() => window.__posted), [{ type: 'rerun' }], layout);
  }
  await p.close();
});

test('R47: the Findings | Both | Graph toggle exists in Split only', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  assert.ok(await p.page.isVisible('#paneSwitch'));
  await p.layout('outline');
  assert.ok(!(await p.page.isVisible('#paneSwitch')));
  await p.layout('list');
  assert.ok(!(await p.page.isVisible('#paneSwitch')));
  await p.layout('split');
  await p.page.click('#paneGraph');
  await p.page.waitForTimeout(60);
  assert.ok(!(await p.page.isVisible('#inspector')));
  await p.layout('outline');
  assert.ok(await p.page.isVisible('#centre'), 'a pane choice made in Split does not hide anything in Outline');
  await p.close();
});

test('Outline: the steps in order with their counts; the centre shows the step\'s estimates and its findings; the side summarises', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  await p.layout('outline');
  const rows = await p.texts('#tree .row');
  assert.ok(rows.length >= 2);
  assert.match(rows[0], /delegate Replier/);
  await p.page.click('#tree [data-node="n1"]');
  await p.page.waitForTimeout(60);
  assert.match((await p.texts('#centre .sechd'))[0], /^4 delegate Replier/);
  assert.match((await p.texts('#centre .est'))[0], /COST OF THIS STEP|Cost of this step/i);
  assert.equal(await p.page.$$eval('#centre [data-finding]', (r) => r.length), 4);
  const side = (await p.texts('#outlineSide'))[0];
  assert.match(side, /High 3/i);
  assert.match(side, /trifecta present/);
  await p.close();
});

test('Outline: with no step chosen the centre lists every finding', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  await p.layout('outline');
  assert.match((await p.texts('#centre .sechd'))[0], /^10 All findings/);
  assert.equal(await p.page.$$eval('#centre [data-finding]', (r) => r.length), 10);
  await p.close();
});

test('List: a table with severity, rule, lens, finding and where; the focused finding is explained beside it', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = await p.show('risky');
  await p.layout('list');
  assert.equal((await p.texts('#table .thead'))[0].toLowerCase(), 'severity rule lens finding where action');
  const first = f.review.findings[0];
  const row = (await p.texts(`[data-finding="${first.fingerprint}"]`))[0];
  assert.ok(row.includes(first.rule) && row.includes(first.title));
  await p.page.click(`[data-finding="${first.fingerprint}"]`);
  await p.page.waitForTimeout(60);
  assert.match((await p.texts('#focus'))[0], new RegExp(first.title.slice(0, 25).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  await p.close();
});

test('List: the minimap puts the findings next to the steps, and a step in it narrows the list', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  await p.layout('list');
  const dots = await p.page.$$eval('#minimap [data-node="n1"] .d i', (n) => n.length);
  assert.equal(dots, 4);
  await p.page.click('#minimap [data-node="n1"]');
  await p.page.waitForTimeout(60);
  assert.equal((await p.ids()).length, 4);
  await p.close();
});

test('R48: below 900 px Split stacks, Outline folds its right column behind a disclosure, List drops the minimap; no sideways scroll in any', async (t) => {
  if (skip(t)) { return; }
  const p = await open('light', { viewport: { width: 640, height: 900 } });
  await p.show('many');
  const noScroll = () => p.page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1);
  const b = async (s) => (await p.page.$(s)).boundingBox();
  const g = await b('#graphpane'), i = await b('#inspector');
  assert.ok(g.y < i.y && Math.abs(g.x - i.x) < 2, 'split stacked');
  assert.ok(await noScroll(), 'split');
  await p.layout('outline');
  assert.equal(await p.page.$eval('#outlineSide', (d) => d.hasAttribute('open')), false, 'the right column is folded');
  assert.ok(await noScroll(), 'outline');
  await p.page.click('#outlineSide > summary');
  assert.equal(await p.page.$eval('#outlineSide', (d) => d.hasAttribute('open')), true, 'and opens on request');
  await p.layout('list');
  assert.ok(!(await p.page.isVisible('#minimap')), 'the minimap is dropped');
  assert.ok(await noScroll(), 'list');
  await p.close();
});

test('R48: at a wide width Outline shows all three columns and List shows the minimap', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  await p.layout('outline');
  assert.ok(await p.page.isVisible('#tree') && await p.page.isVisible('#centre') && await p.page.isVisible('#outlineSide'));
  assert.equal(await p.page.$eval('#outlineSide', (d) => d.hasAttribute('open')), true);
  await p.layout('list');
  assert.ok(await p.page.isVisible('#minimap'));
  await p.close();
});

test('R41: in the List and Outline layouts the rows are a listbox and the arrow keys move the selection', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show('risky');
  for (const layout of ['outline', 'list']) {
    await p.layout(layout);
    assert.equal(await p.page.getAttribute('#flist', 'role'), layout === 'x' ? '' : 'listbox');
    const first = await p.page.$('#flist [data-finding]');
    await first.focus();
    await p.page.keyboard.press('ArrowDown');
    await p.page.waitForTimeout(60);
    assert.equal(await p.page.$$eval('#flist [aria-selected="true"]', (n) => n.length), 1, layout);
  }
  await p.close();
});

test('the layouts hold in the dark theme and nothing is written as markup', async (t) => {
  if (skip(t)) { return; }
  const p = await open('dark');
  const f = fixture('risky');
  f.review.findings[0].title = '<img src=x onerror="window.__pwned=1">';
  await p.page.evaluate((m) => window.postMessage(m, '*'), { type: 'review', review: f.review, graph: f.graph, status: 'idle' });
  for (const layout of ['split', 'outline', 'list']) {
    await p.layout(layout);
    assert.equal(await p.page.$$eval('#main img', (n) => n.length), 0, layout);
  }
  assert.equal(await p.page.evaluate(() => window.__pwned), undefined);
  assert.deepEqual(p.errors, []);
  await p.close();
});
