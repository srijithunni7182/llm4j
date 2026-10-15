'use strict';
/* R23: 500 tools and 200 agent rows draw within a fixed budget, stay responsive, and say when the page holds rows back. */
const test = require('node:test');
const assert = require('node:assert/strict');
const { missing, launch, startFixtures, openRun } = require('./support');

const skip = missing();
let fixtures; let browser;
test.before(async () => { if (skip) return; fixtures = await startFixtures(['trifecta'], null, true); browser = await launch(); });
test.after(async () => { if (browser) await browser.close(); if (fixtures) fixtures.stop(); });

function tools(n) {
  const rows = [];
  const counts = { failing: 0, degraded: 0, healthy: 0, unused: 0 };
  for (let i = 0; i < n; i++) {
    const state = ['failing', 'degraded', 'healthy', 'healthy', 'unused'][i % 5];
    counts[state]++;
    rows.push({ name: 'Tool' + String(i).padStart(3, '0'), kind: i % 7 === 0 ? 'task' : 'tool', calls: 100 + i, ran: 90, successes: state === 'failing' ? 20 : 88, softFailures: 0, p50Ms: 25, p95Ms: 500,
      histogram: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12], histogramBounds: [10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000, 30000], recent: ['', '', 'timeout', '', '', '', 'timeout', '', '', ''],
      agents: ['A' + (i % 9)], lastRun: state === 'failing' ? { client: 'shop', traceId: 'abc', at: new Date().toISOString(), category: 'timeout' } : undefined,
      health: { state, reason: 'because ' + i, failures: state === 'failing' ? { timeout: 70 } : {}, byAgent: { A1: { calls: 5, successes: 4, failures: 1 } }, lastFailure: state === 'failing' ? { category: 'timeout', at: new Date().toISOString() } : undefined } });
  }
  return { rows, counts };
}
function security(n) {
  const agents = [];
  for (let i = 0; i < n; i++) agents.push({ client: 'shop' + (i % 5), traceId: 't' + i, runId: 'r' + i, workflow: 'Main', agent: 'Agent' + i, verdict: ['exercised', 'accepted', 'armed'][i % 3], assumed: false, acceptedReason: i % 3 === 1 ? 'reviewed' : undefined });
  return { counts: { trifectaExercised: 66, trifectaAccepted: 67, injectionSuspect: 3, accessRefused: 4, unknownTools: 1, approvalsRefused: 0, piiMasked: 0, piiBlocked: 0, piiDetected: 0, bias: 0 }, agents, incidents: [] };
}

test('R23: 500 tools draw the first 200 within the budget, say so, and a filter answers fast', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base);
  await page.route(/\/api\/tools(\?.*)?$/, (route) => route.fulfill({ json: tools(500) }));
  const t0 = Date.now();
  await page.locator('#wtabs button.proc', { hasText: 'Tools & tasks' }).click();
  await page.waitForSelector('#ftrows tr.tt');
  const drawn = Date.now() - t0;
  assert.ok(drawn < 3000, 'first draw took ' + drawn + ' ms');
  assert.equal(await page.locator('#ftrows tr.tt').count(), 200);
  assert.match(await page.locator('#ftmore').innerText(), /first 200 of 500/);
  const nodes = await page.evaluate(() => document.getElementsByTagName('*').length);
  assert.ok(nodes < 9000, nodes + ' elements');
  const t1 = Date.now();
  await page.locator('#ftf-health button', { hasText: 'failing' }).click();
  await page.waitForFunction(() => document.querySelectorAll('#ftrows tr.tt').length === 100);
  assert.ok(Date.now() - t1 < 800, 'a filter took ' + (Date.now() - t1) + ' ms');
  assert.equal(await page.locator('#ftmore').isHidden(), true, 'no note when nothing is held back');
  await context.close();
});

test('R23: 200 agent rows on the Security page draw within the budget', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base);
  await page.route(/\/api\/security(\?.*)?$/, (route) => route.fulfill({ json: security(200) }));
  const t0 = Date.now();
  await page.locator('#wtabs button.proc', { hasText: 'Security' }).click();
  await page.waitForSelector('#fsagents tr');
  await page.waitForFunction(() => document.querySelectorAll('#fsagents tr').length === 200);
  assert.ok(Date.now() - t0 < 3000, 'draw took ' + (Date.now() - t0) + ' ms');
  await context.close();
});
