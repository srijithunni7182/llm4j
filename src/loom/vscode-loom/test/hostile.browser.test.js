'use strict';
/*
 * R29 and A10 of spec 10, in a real page: the text of a script is the one thing the page cannot trust. Every field a finding, a step or
 * a file name can reach is filled with markup, handlers, scripts, bidirectional controls and enormous words, in every layout; none of
 * it runs, none of it becomes an element, all of it is shown as the text it is, and the page stays usable at a large size.
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

const pw = loadPlaywright();
let browser, htmlFile;
test.before(async () => {
  if (!pw) { return; }
  try { browser = await pw.chromium.launch(); } catch (e) { browser = null; }
  htmlFile = path.join(os.tmpdir(), 'loom-review-hostile-test.html');
  fs.writeFileSync(htmlFile, renderReviewHtml({ cspSource: 'file:', nonce: 'n', uri: (name) => 'file://' + path.join(MEDIA, name) }));
});
test.after(async () => { if (browser) { await browser.close(); } });
const skip = (t) => { if (!pw || !browser) { t.skip('playwright or a browser is not installed'); return true; } return false; };

const HOSTILE = [
  '<img src=x onerror="window.__pwned=1">',
  '<script>window.__pwned=1</script>',
  '<svg onload="window.__pwned=1"></svg>',
  '"><iframe src="javascript:window.__pwned=1"></iframe>',
  '<a href="javascript:window.__pwned=1">click</a>',
  '</script><script>window.__pwned=1</script>',
  '‮⁦ right to left ⁩',
  '\u0000​‍﻿ zero width',
  '{{constructor.constructor("window.__pwned=1")()}}',
  '`${window.__pwned=1}`',
  'a'.repeat(3000)
];
const ONE = HOSTILE.slice(0, 9).join(' ');

async function open(viewport) {
  const context = await browser.newContext({ viewport: viewport || { width: 1100, height: 800 } });
  context.setDefaultTimeout(5000);
  const page = await context.newPage();
  const errors = [];
  const outside = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('dialog', async (d) => { errors.push('a dialog opened: ' + d.message()); await d.dismiss(); });
  page.on('request', (r) => { if (!r.url().startsWith('file:') && !r.url().startsWith('data:')) { outside.push(r.url()); } });
  await page.addInitScript(() => { window.__posted = []; window.acquireVsCodeApi = () => ({ postMessage: (m) => window.__posted.push(JSON.parse(JSON.stringify(m))), getState: () => null, setState: () => {} }); });
  await page.goto('file://' + htmlFile);
  await page.evaluate(() => { document.body.className = 'vscode-light'; });
  return { page, errors, outside, close: () => context.close() };
}

function poison(f) {
  const w = f.graph.workflows[0];
  w.name = ONE;
  w.nodes.forEach((n, i) => { n.label = HOSTILE[i % HOSTILE.length]; if (n.attrs) { n.attrs = n.attrs; } });
  f.review.findings.forEach((x, i) => {
    x.title = HOSTILE[i % HOSTILE.length] + ' title';
    x.why = HOSTILE[(i + 1) % HOSTILE.length] + ' why';
    x.fixHint = HOSTILE[(i + 2) % HOSTILE.length] + ' fix';
    x.location.file = '<b>file</b>' + HOSTILE[(i + 3) % HOSTILE.length] + '.loom';
    x.rule = x.rule;
  });
  return f;
}

for (const layout of ['split', 'outline', 'list']) {
  test(`R29: hostile text in every field is only ever text (${layout})`, async (t) => {
    if (skip(t)) { return; }
    const p = await open();
    const f = poison(fixture('risky'));
    await p.page.evaluate((m) => window.postMessage(m, '*'), { type: 'review', review: f.review, graph: f.graph, status: 'idle', entry: '/p/' + ONE + '.loom' });
    await p.page.waitForTimeout(100);
    await p.page.click(`[data-layout="${layout}"]`);
    await p.page.waitForTimeout(80);
    // use the page: select findings, filter by the hostile text, group, open details
    for (let i = 0; i < 4; i++) { await p.page.locator('#main [data-finding]').nth(i).click(); await p.page.waitForTimeout(40); }
    await p.page.fill('#filter', '<img');
    await p.page.waitForTimeout(60);
    await p.page.fill('#filter', '');
    for (const g of ['#groupLens', '#groupRule', '#groupFile', '#groupSeverity']) { await p.page.click(g); await p.page.waitForTimeout(30); }
    assert.equal(await p.page.evaluate(() => window.__pwned), undefined, 'nothing ran');
    assert.deepEqual(p.errors, []);
    assert.deepEqual(p.outside, []);
    // nothing in the page became an element: no image, frame, svg, link to javascript:, inline handler, or extra script
    assert.equal(await p.page.$$eval('#main img, #main iframe, #main svg[onload], #main a[href^="javascript"], #main object, #main embed', (n) => n.length), 0);
    assert.equal(await p.page.$$eval('*', (all) => all.filter((el) => [...el.attributes].some((a) => /^on/i.test(a.name))).length), 0, 'no inline handlers');
    assert.equal(await p.page.$$eval('script', (n) => n.length), 4, 'only the four scripts of the page');
    assert.equal(await p.page.$$eval('#main b', (n) => n.filter((x) => x.textContent === 'file').length), 0);
    // and the text is there, as written
    const body = await p.page.innerText('#main');
    assert.ok(body.includes('<img src=x') || body.includes('title'), 'the finding text is shown');
    await p.close();
  });
}

test('R29: a hostile error message and a hostile banner are shown as text', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = fixture('risky');
  await p.page.evaluate((m) => window.postMessage(m, '*'), { type: 'review', review: f.review, graph: f.graph, status: 'idle', entry: '/p/x.loom' });
  await p.page.evaluate((m) => window.postMessage(m, '*'), { type: 'status', status: 'error', message: ONE });
  await p.page.waitForTimeout(100);
  assert.equal(await p.page.evaluate(() => window.__pwned), undefined);
  assert.equal(await p.page.$$eval('#banner img, #banner script, #banner svg, #banner iframe', (n) => n.length), 0);
  assert.deepEqual(p.errors, []);
  await p.close();
});

test('A10: words with no break, in every field, never push the page sideways at a narrow width', async (t) => {
  if (skip(t)) { return; }
  for (const layout of ['split', 'outline', 'list']) {
    const p = await open({ width: 420, height: 800 });
    const f = poison(fixture('risky'));
    f.review.findings.forEach((x) => { x.title = 'w'.repeat(500); x.why = 'v'.repeat(500) + '/' + 'u'.repeat(500); x.location.file = 'f'.repeat(300) + '.loom'; });
    await p.page.evaluate((m) => window.postMessage(m, '*'), { type: 'review', review: f.review, graph: f.graph, status: 'idle', entry: '/p/x.loom' });
    await p.page.waitForTimeout(100);
    await p.page.click(`[data-layout="${layout}"]`);
    await p.page.waitForTimeout(80);
    const wide = await p.page.evaluate(() => ({ page: document.documentElement.scrollWidth, view: document.documentElement.clientWidth }));
    assert.ok(wide.page <= wide.view + 1, `${layout}: ${wide.page} px wide in ${wide.view}`);
    await p.close();
  }
});

test('A6: 2,000 findings still draw in a few seconds and stay responsive', async (t) => {
  if (skip(t)) { return; }
  const p = await open({ width: 1200, height: 800 });
  const f = fixture('many');
  const base = f.review.findings;
  const findings = [];
  for (let i = 0; i < 2000; i++) { const x = JSON.parse(JSON.stringify(base[i % base.length])); x.fingerprint = 'fp' + i; x.title = x.title + ' #' + i; findings.push(x); }
  f.review.findings = findings;
  f.review.summary.open = findings.length;
  const t0 = Date.now();
  await p.page.evaluate((m) => window.postMessage(m, '*'), { type: 'review', review: f.review, graph: f.graph, status: 'idle', entry: '/p/many.loom' });
  await p.page.waitForSelector('#main [data-finding]');
  const drawn = Date.now() - t0;
  console.log(`# 2,000 findings drawn in ${drawn} ms`);
  assert.ok(drawn < 4000, `drawn in ${drawn} ms`);
  const t1 = Date.now();
  await p.page.click('#main [data-finding]');
  await p.page.fill('#filter', '#19');
  await p.page.waitForTimeout(50);
  const responded = Date.now() - t1;
  console.log(`# select and filter among 2,000: ${responded} ms`);
  assert.ok(responded < 2500, `responded in ${responded} ms`);
  await p.close();
});
