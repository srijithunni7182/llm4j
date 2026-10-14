'use strict';
/* R12, R13: the two pages that look across every run: Tools & tasks and Security. A Fabric-shaped fixture server over the simulator's scenarios, a real Chromium. */
const test = require('node:test');
const assert = require('node:assert/strict');
const { missing, launch, startFixtures, openRun } = require('./support');

const SCENARIOS = ['flaky-tool', 'task-broken', 'task-diverted', 'trifecta', 'trifecta-accepted', 'injected-page', 'access-refused', 'context-fills'];
const skip = missing();
let fixtures; let browser;

test.before(async () => {
  if (skip) return;
  fixtures = await startFixtures(SCENARIOS, null, true);
  browser = await launch();
});
test.after(async () => {
  if (browser) await browser.close();
  if (fixtures) fixtures.stop();
});

async function text(page, sel) { return (await page.locator(sel).first().innerText()).replace(/\s+/g, ' ').trim(); }
async function api(page, path) { return page.evaluate((p) => fetch(p).then((r) => r.json()), path); }
async function fleet(name) {
  const o = await openRun(browser, fixtures.base);
  await o.page.locator('#wtabs button.proc', { hasText: name }).click();
  return o;
}

test('R12/R23: the tab row carries Tools & tasks and Security, with a count each', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base);
  const tools = page.locator('#wtabs button.proc', { hasText: 'Tools & tasks' });
  const sec = page.locator('#wtabs button.proc', { hasText: 'Security' });
  await tools.waitFor();
  await sec.waitFor();
  const runs = await api(page, '/api/runs');
  const open = runs.runs.reduce((n, r) => n + (r.security || 0), 0);
  assert.ok(runs.fleet.toolsFailing > 0, 'the scenarios include failing tools');
  assert.ok(open > 0, 'the scenarios include open security incidents');
  assert.equal((await tools.locator('.inc').innerText()).trim(), String(runs.fleet.toolsFailing));
  assert.equal((await sec.locator('.inc').innerText()).trim(), String(open));
  await context.close();
});

test('R12: the Tools & tasks page counts by health and lists every run\'s rows, worst first, as the API does', { skip: skip || false }, async () => {
  const { page, context, problems } = await fleet('Tools & tasks');
  await page.waitForSelector('#ftrows tr.tt');
  const a = await api(page, '/api/tools');
  for (const state of ['failing', 'degraded', 'healthy', 'unused']) {
    assert.equal((await text(page, '#ft-' + state + ' [data-v]')), String(a.counts[state]), state + ' count');
  }
  const names = await page.locator('#ftrows tr.tt td.nm-cell .nm').allInnerTexts();
  assert.deepEqual(names, a.rows.map((r) => r.name));
  assert.match(await page.locator('#ftrows tr.tt').first().innerText(), /failing/);
  assert.ok(await page.locator('#ftrows tr.tt').first().locator('.recent i.x').count() > 0, 'the failing row\'s last-ten strip shows its failures');
  assert.match(await text(page, '#ftrows tr.tt .rate'), /0 of 3|0 of 6|of \d+/);
  assert.match(await text(page, '#ftrows tr.tt'), /(timeout|rate_limited|server_error|permission_denied|[a-z_]+)/);
  assert.deepEqual(problems, []);
  await context.close();
});

test('R12: the three filters narrow the table and say what they are doing', { skip: skip || false }, async () => {
  const { page, context } = await fleet('Tools & tasks');
  await page.waitForSelector('#ftrows tr.tt');
  const all = await page.locator('#ftrows tr.tt').count();
  await page.locator('#ftf-health button', { hasText: 'failing' }).click();
  const failing = await page.locator('#ftrows tr.tt').count();
  assert.ok(failing > 0 && failing < all);
  for (const t of await page.locator('#ftrows tr.tt .hw').allInnerTexts()) assert.equal(t.trim(), 'failing');
  await page.locator('#ftf-health button', { hasText: 'all' }).click();
  await page.locator('#ftf-kind button', { hasText: /^tasks$/ }).click();
  const kinds = await page.locator('#ftrows tr.tt td:nth-child(2)').allInnerTexts();
  assert.ok(kinds.length > 0);
  for (const k of kinds) assert.equal(k.trim(), 'task');
  await page.locator('#ftf-kind button', { hasText: /^tools$/ }).click();
  for (const k of await page.locator('#ftrows tr.tt td:nth-child(2)').allInnerTexts()) assert.equal(k.trim(), 'tool');
  await page.locator('#ftf-kind button', { hasText: 'tools and tasks' }).click();
  await page.locator('#ftf-proc button', { hasText: 'flaky-tool' }).click();
  const a = await api(page, '/api/tools?client=flaky-tool');
  await page.waitForFunction((n) => document.querySelectorAll('#ftrows tr.tt').length === n, a.rows.length);
  const only = await page.locator('#ftrows tr.tt td.nm-cell .nm').allInnerTexts();
  assert.deepEqual(only, a.rows.map((r) => r.name));
  await context.close();
});

test('R12: a row opens to its failures and a link to the run it last failed in, and the order holds while it is open', { skip: skip || false }, async () => {
  const { page, context } = await fleet('Tools & tasks');
  await page.waitForSelector('#ftrows tr.tt');
  const before = await page.locator('#ftrows tr.tt td.nm-cell .nm').allInnerTexts();
  const first = page.locator('#ftrows tr.tt').first();
  await first.click();
  const detail = page.locator('#ftrows tr.ttdetail');
  await detail.waitFor();
  assert.match(await detail.innerText(), /failures by category/i);
  const link = detail.locator('button.runlink');
  assert.match(await link.innerText(), /Open the run it last failed in/);
  await page.waitForTimeout(2500); // two polls
  assert.deepEqual(await page.locator('#ftrows tr.tt td.nm-cell .nm').allInnerTexts(), before, 'the order does not move under an open row');
  assert.equal(await page.locator('#ftrows tr.ttdetail').count(), 1, 'the row is still open');
  await link.click();
  await page.waitForSelector('#main:not([hidden])');
  assert.equal(await page.locator('#fleettools').isHidden(), true);
  await page.waitForSelector('#toolspanel:not([hidden])');
  await context.close();
});

test('R13: the Security page counts, lists the agents that matter with their reason, and the open findings with links', { skip: skip || false }, async () => {
  const { page, context, problems } = await fleet('Security');
  await page.waitForSelector('#fsagents tr');
  const a = await api(page, '/api/security');
  assert.match(await text(page, '#fs-trifecta [data-v]'), new RegExp(a.counts.trifectaExercised + ' agent'));
  assert.match(await text(page, '#fs-injection [data-v]'), /suspect/);
  assert.match(await text(page, '#fs-access [data-v]'), new RegExp(a.counts.accessRefused + ' refused'));
  const rows = await page.locator('#fsagents tr').allInnerTexts();
  assert.equal(rows.length, a.agents.length);
  assert.ok(rows.some((r) => /exercised/.test(r)));
  assert.ok(rows.some((r) => /accepted/.test(r) && /accepted:/.test(r)), 'an accepted row shows its reason');
  const finds = page.locator('#fsfind .incident');
  assert.equal(await finds.count(), a.incidents.length);
  assert.ok(await page.locator('#fsfind button.runlink').count() > 0, 'a finding links to its run');
  assert.match(await text(page, '#fsstatement'), /cannot tell you/);
  assert.deepEqual(problems, []);
  await context.close();
});

test('R13: a finding\'s link opens that run on its Security view', { skip: skip || false }, async () => {
  const { page, context } = await fleet('Security');
  await page.waitForSelector('#fsfind .incident');
  await page.locator('#fsfind button.runlink').first().click();
  await page.waitForSelector('#main:not([hidden])');
  await page.waitForSelector('#securitypanel:not([hidden])');
  assert.equal(await page.locator('#fleetsec').isHidden(), true);
  await context.close();
});

test('R13: with nothing notable the Security page says why it is empty', { skip: skip || false }, async () => {
  const calm = await startFixtures(['context-fills'], null, true);
  try {
    const o = await openRun(browser, calm.base);
    await o.page.locator('#wtabs button.proc', { hasText: 'Security' }).click();
    await o.page.waitForSelector('#nofsagents:not([hidden])');
    assert.match(await text(o.page, '#nofsagents'), /No agent has used all three|No agent/);
    assert.match(await text(o.page, '#nofsfind'), /No open security finding/);
    await o.context.close();
  } finally { calm.stop(); }
});
