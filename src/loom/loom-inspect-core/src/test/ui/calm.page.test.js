'use strict';
/* R26, R27, R28, R35: a page that holds still. Tabs keep their order, a run list replaces a tab per run, rows keep their place, and Pause stops every redraw. */
const test = require('node:test');
const assert = require('node:assert/strict');
const { missing, launch, startFixtures, openRun, openTab } = require('./support');

const SCENARIOS = ['context-fills', 'no-window', 'flaky-tool', 'task-diverted', 'task-broken', 'delegate-broken', 'trifecta', 'trifecta-accepted', 'injected-page', 'access-refused', 'truncated-answers'];
const skip = missing();
let fixtures; let browser;
test.before(async () => { if (skip) return; fixtures = await startFixtures(SCENARIOS, null, true); browser = await launch(); });
test.after(async () => { if (browser) await browser.close(); if (fixtures) fixtures.stop(); });

const runTabs = (page) => page.locator('#wtabs button.run').allInnerTexts().then((a) => a.map((t) => t.replace(/\s+/g, ' ').trim()));
const text = async (page, sel) => (await page.locator(sel).first().innerText()).replace(/\s+/g, ' ').trim();

/** Serves /api/runs through a function of the real answer and the poll number, so a test can make the server shuffle, add and finish runs. */
async function shape(page, fn) {
  let n = 0;
  await page.route(/\/api\/runs(\?.*)?$/, async (route) => {
    const r = await route.fetch();
    const j = await r.json();
    n++;
    await route.fulfill({ response: r, json: fn(j, n) });
  });
}
const shuffle = (a, seed) => a.map((x, i) => [x, Math.sin(seed * 7 + i * 13)]).sort((p, q) => p[1] - q[1]).map((p) => p[0]);

test('R26: tabs keep the order they first appeared in, however the server orders its list', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base);
  await shape(page, (j, n) => ({ runs: shuffle(j.runs.map((r, i) => (i < 6 ? Object.assign({}, r, { status: 'running' }) : r)), n), fleet: j.fleet }));
  await page.waitForTimeout(1500);
  const first = await runTabs(page);
  assert.ok(first.length >= 2, 'several run tabs');
  for (let i = 0; i < 4; i++) { await page.waitForTimeout(1100); assert.deepEqual(await runTabs(page), first, 'poll ' + i); }
  await context.close();
});

test('R26: a new run is added at the end and never takes the selection', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'trifecta');
  await page.waitForSelector('#wtabs button.run.on');
  const selected = await text(page, '#wtabs button.run.on');
  const before = await runTabs(page);
  await shape(page, (j, n) => {
    const live = j.runs.map((r, i) => (i < 5 && !/trifecta\//.test(r.client + '/') ? Object.assign({}, r, { status: 'running' }) : r));
    if (n < 2) return { runs: live, fleet: j.fleet };
    const base = j.runs[0];
    return { fleet: j.fleet, runs: [Object.assign({}, base, { traceId: 'f'.repeat(32), runId: 'new-run', workflow: 'Brand new', status: 'running', startedAt: new Date().toISOString(), incidents: 0, security: 0 })].concat(j.runs) };
  });
  await page.waitForTimeout(2500);
  const after = await runTabs(page);
  assert.deepEqual(after.slice(0, before.length), before, 'the tabs that were there did not move');
  assert.match(after[after.length - 1], /Brand new/);
  assert.equal(await text(page, '#wtabs button.run.on'), selected, 'the selection did not change');
  assert.match(await text(page, '#wfname'), /Main/);
  await context.close();
});

test('R35: the tab row holds at most eight runs and a Runs list holds all of them', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base);
  await page.waitForSelector('#wtabs button.proc');
  assert.ok((await page.locator('#wtabs button.run').count()) <= 8);
  const btn = page.locator('#wtabs button.proc', { hasText: 'Runs' });
  await btn.waitFor();
  const all = (await page.evaluate(() => fetch('/api/runs').then((r) => r.json()))).runs.length;
  assert.match(await btn.innerText(), new RegExp(String(all)));
  await btn.click();
  await page.waitForSelector('#rlrows tr.rl');
  assert.equal(await page.locator('#rlrows tr.rl').count(), all);
  const sample = await text(page, '#rlrows tr.rl');
  assert.match(sample, /\d\d:\d\d/, 'a start time');
  await page.fill('#rl-search', 'trifecta');
  await page.waitForFunction(() => document.querySelectorAll('#rlrows tr.rl').length === 2);
  await page.fill('#rl-search', '');
  await page.locator('#rl-status button', { hasText: 'failed' }).click();
  for (const t of await page.locator('#rlrows tr.rl .st').allInnerTexts()) assert.match(t.trim(), /failed/);
  await page.locator('#rl-status button', { hasText: 'all' }).click();
  const row = page.locator('#rlrows tr.rl', { hasText: 'truncated-answers' }).first();
  await row.click();
  await page.waitForSelector('#main:not([hidden])');
  await page.waitForFunction(() => /truncated-answers/.test(document.getElementById('client').textContent));
  assert.match(await text(page, '#wtabs button.run.on'), /truncated-answers/, 'the chosen run is in the tab row while it is selected');
  await context.close();
});

test('R35: two runs of one workflow are told apart by when they started', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base);
  await shape(page, (j) => {
    const a = j.runs[0];
    return { runs: j.runs.concat([Object.assign({}, a, { traceId: 'e'.repeat(32), runId: 'twin', startedAt: '2026-01-01T09:15:00Z' })]) };
  });
  await page.locator('#wtabs button.proc', { hasText: 'Runs' }).click();
  await page.waitForSelector('#rlrows tr.rl');
  assert.ok(await page.locator('#rlrows tr.group').count() >= 1, 'a workflow with several runs gets a heading');
  const rows = await page.locator('#rlrows tr.rl', { hasText: await page.evaluate(() => fetch('/api/runs').then((r) => r.json()).then((j) => j.runs[0].client)) }).allInnerTexts();
  assert.ok(rows.length >= 2);
  assert.notEqual(rows[0].replace(/\s+/g, ' '), rows[1].replace(/\s+/g, ' '));
  await context.close();
});

test('R35: a run that finishes leaves the tab row after a minute, unless it is selected', { skip: skip || false }, async () => {
  const context = await browser.newContext({ viewport: { width: 1360, height: 900 } });
  const page = await context.newPage();
  await page.clock.install();
  let phase = 'running';
  await page.route(/\/api\/runs(\?.*)?$/, async (route) => {
    const r = await route.fetch();
    const j = await r.json();
    const one = Object.assign({}, j.runs[0], { traceId: 'a'.repeat(32), runId: 'sprint', workflow: 'Sprint', status: phase, incidents: 0, security: 0, startedAt: '2026-01-01T09:00:00Z' });
    const other = Object.assign({}, j.runs[0], { traceId: 'b'.repeat(32), runId: 'steady', workflow: 'Marathon', status: 'running', incidents: 0, security: 0, startedAt: '2026-01-01T09:01:00Z' });
    delete one.worstIncident; delete other.worstIncident;
    await route.fulfill({ response: r, json: { runs: [one, other], fleet: j.fleet } });
  });
  await page.goto(fixtures.base);
  await page.clock.runFor(2000);
  await page.waitForSelector('#wtabs button.run');
  assert.match((await runTabs(page)).join('|'), /Sprint/);
  phase = 'finished';
  await page.clock.runFor(30000);
  await new Promise((r) => setTimeout(r, 800));
  assert.match((await runTabs(page)).join('|'), /Sprint/, 'still shown while fresh');
  await page.locator('#wtabs button.run', { hasText: 'Marathon' }).click();   // the person is looking at another run
  await new Promise((r) => setTimeout(r, 600));
  await page.clock.runFor(70000);
  await new Promise((r) => setTimeout(r, 800));
  await page.clock.runFor(1500);
  await new Promise((r) => setTimeout(r, 800));
  assert.doesNotMatch((await runTabs(page)).join('|'), /Sprint/, 'gone after a minute');
  await context.close();
});

test('R27: a table keeps its rows where they are, says the order changed and re-sorts on request', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base);
  let flip = false;
  await page.route(/\/api\/tools(\?.*)?$/, async (route) => {
    const r = await route.fetch();
    const j = await r.json();
    if (flip && j.rows.length > 1) j.rows = j.rows.slice().reverse();
    await route.fulfill({ response: r, json: j });
  });
  await page.locator('#wtabs button.proc', { hasText: 'Tools & tasks' }).click();
  await page.waitForSelector('#ftrows tr.tt');
  const names = () => page.locator('#ftrows tr.tt td.nm-cell .nm').allInnerTexts();
  const first = await names();
  assert.equal(await page.locator('#ftsort').isHidden(), true, 'no chip while the order is the live order');
  flip = true;
  await page.waitForSelector('#ftsort:not([hidden])');
  assert.deepEqual(await names(), first, 'the rows did not move');
  assert.match(await text(page, '#ftsortwrap'), /order changed/i);
  await page.click('#ftsort');
  await page.waitForFunction((was) => document.querySelector('#ftrows tr.tt td.nm-cell .nm').textContent !== was, first[0]);
  assert.deepEqual(await names(), first.slice().reverse());
  assert.equal(await page.locator('#ftsort').isHidden(), true);
  await context.close();
});

test('R27: the same holds on a run\'s own Tools & tasks table', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'flaky-tool');
  let flip = false;
  await page.route(/\/api\/runs\/[0-9a-f]+$/, async (route) => {
    const r = await route.fetch();
    const j = await r.json();
    if (flip && j.tools) j.tools = j.tools.map((x) => (x.name === 'Steady' ? Object.assign({}, x, { calls: 1000, health: Object.assign({}, x.health, { state: 'failing' }) }) : x));
    await route.fulfill({ response: r, json: j });
  });
  await openTab(page, 'Tools');
  await page.waitForSelector('#ttrows tr.tt');
  const names = () => page.locator('#ttrows tr.tt td:first-child .nm').allInnerTexts();
  const first = await names();
  flip = true;
  await page.waitForSelector('#ttsort:not([hidden])');
  assert.deepEqual(await names(), first);
  await page.click('#ttsort');
  await page.waitForFunction((was) => document.querySelector('#ttrows tr.tt td:first-child .nm').textContent !== was, first[0]);
  await context.close();
});

test('R28: Paused stops every redraw, counts what changed, and Live redraws at once', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'trifecta');
  await page.waitForSelector('#wtabs button.run.on');
  let extra = false;
  await shape(page, (j) => {
    if (!extra) return j;
    const base = j.runs[0];
    return { fleet: j.fleet, runs: j.runs.concat([Object.assign({}, base, { traceId: 'd'.repeat(32), runId: 'late', workflow: 'Latecomer', status: 'running', startedAt: new Date().toISOString() })]) };
  });
  await page.waitForTimeout(1200);
  assert.match(await text(page, '#live'), /Live/);
  await page.click('#live');
  assert.match(await text(page, '#live'), /Paused/);
  assert.equal(await page.locator('#live').getAttribute('aria-pressed'), 'true');
  const frozen = await page.locator('body').innerHTML().then((h) => h.replace(/\s+/g, ' ').replace(/<span id="livechip"[^>]*>.*?<\/span>/, '').replace(/updated [0-9:]+/g, ''));
  extra = true;
  await page.waitForFunction(() => /1 change/.test(document.getElementById('livechip').textContent), null, { timeout: 5000 });
  assert.match(await text(page, '#livechip'), /Paused at \d\d:\d\d:\d\d/);
  const still = await page.locator('body').innerHTML().then((h) => h.replace(/\s+/g, ' ').replace(/<span id="livechip"[^>]*>.*?<\/span>/, '').replace(/updated [0-9:]+/g, ''));
  assert.equal(still, frozen, 'nothing on the page moved while paused');
  // a person can still navigate while paused: one refresh, for what they asked
  await page.locator('#wtabs button.proc', { hasText: 'Security' }).click();
  await page.waitForSelector('#fleetsec:not([hidden])');
  assert.match(await text(page, '#live'), /Paused/);
  await page.click('#live');
  assert.match(await text(page, '#live'), /Live/);
  await page.waitForFunction(() => /Latecomer/.test(document.getElementById('wtabs').textContent) || /Runs/.test(document.getElementById('wtabs').textContent));
  await context.close();
});
