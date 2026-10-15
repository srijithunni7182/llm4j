'use strict';
/* R21: no console error, page error or failed request in any scenario; one extra poll at most while a fleet page is open. */
const test = require('node:test');
const assert = require('node:assert/strict');
const { missing, launch, startFixtures, openRun, openTab } = require('./support');

const SCENARIOS = ['context-fills', 'no-window', 'flaky-tool', 'task-diverted', 'task-broken', 'delegate-broken', 'trifecta', 'trifecta-accepted', 'injected-page', 'access-refused', 'truncated-answers'];
const skip = missing();
let fixtures; let browser;
test.before(async () => { if (skip) return; fixtures = await startFixtures(SCENARIOS, null, true); browser = await launch(); });
test.after(async () => { if (browser) await browser.close(); if (fixtures) fixtures.stop(); });

test('R21: every scenario, every view and both fleet pages run without a console error, page error or failed request', { skip: skip || false }, async () => {
  const all = [];
  for (const s of SCENARIOS) {
    const { page, context, problems } = await openRun(browser, fixtures.base, s);
    await page.waitForSelector('#runtabs .rt');
    for (const t of ['Overview', 'Context', 'Tools', 'Security']) { await openTab(page, t); await page.waitForTimeout(150); }
    await page.locator('#wtabs button.proc', { hasText: 'Tools & tasks' }).click(); await page.waitForTimeout(300);
    await page.locator('#wtabs button.proc', { hasText: 'Security' }).click(); await page.waitForTimeout(300);
    await page.click('#theme'); await page.waitForTimeout(100);
    problems.forEach((p) => all.push(s + ': ' + p));
    await context.close();
  }
  assert.deepEqual(all, []);
});

test('R21: a fleet page costs one request more per second than the run page it replaces', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'trifecta');
  await page.waitForSelector('#runtabs .rt');
  const count = async (ms) => {
    const seen = {};
    const on = (r) => { const u = new URL(r.url()); if (u.pathname.startsWith('/api/')) seen[u.pathname.replace(/\/[0-9a-f]{16,}.*/, '/{id}')] = (seen[u.pathname.replace(/\/[0-9a-f]{16,}.*/, '/{id}')] || 0) + 1; };
    page.on('request', on);
    await page.waitForTimeout(ms);
    page.off('request', on);
    return seen;
  };
  const run = await count(3200);
  await page.locator('#wtabs button.proc', { hasText: 'Tools & tasks' }).click();
  await page.waitForSelector('#ftrows tr.tt');
  const tools = await count(3200);
  await page.locator('#wtabs button.proc', { hasText: 'Security' }).click();
  await page.waitForSelector('#fsagents tr');
  const sec = await count(3200);
  const total = (m) => Object.values(m).reduce((a, b) => a + b, 0);
  const paths = (m) => Object.keys(m).sort();
  assert.ok(total(tools) <= total(run) + 4, 'tools page: ' + JSON.stringify(tools) + ' vs run ' + JSON.stringify(run));
  assert.ok(total(sec) <= total(run) + 4, 'security page: ' + JSON.stringify(sec) + ' vs run ' + JSON.stringify(run));
  assert.ok(paths(tools).filter((p) => !paths(run).includes(p)).length <= 2, 'only the fleet route is new: ' + paths(tools));
  assert.ok(total(run) / 3.2 <= 6, 'the run page asks at most six things per second');
  await context.close();
});
