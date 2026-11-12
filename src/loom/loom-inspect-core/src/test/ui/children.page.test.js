'use strict';
/* Phase 34e, D16 and F10: the Children section of a run page, in a real Chromium, against a real fixture server. The run view the page asks for
 * gets children added on the way (what a parent with children would send), so the page's own JavaScript draws them: what each child last
 * reported, and how many signals each side has not read. A child with no such attributes (an older process) draws as before, and what a
 * child reports is text and never markup. */
const test = require('node:test');
const assert = require('node:assert/strict');
const { missing, launch, startFixtures, openRun } = require('./support');

const skip = missing();
let fixtures; let browser;

test.before(async () => {
  if (skip) return;
  fixtures = await startFixtures(['flaky-tool'], null, true);
  browser = await launch();
});
test.after(async () => {
  if (browser) await browser.close();
  if (fixtures) fixtures.stop();
});

const CHILDREN = [
  { traceId: 'c1', workflow: 'Research', status: 'running', startedAt: '2026-10-14T10:00:01Z', totals: { tokens: 1200, cost: '0.01' },
    progress: { stage: 'reading', done: 1 }, signals: { toChild: 2, toParent: 1 } },
  { traceId: 'c2', workflow: 'Research', status: 'finished', startedAt: '2026-10-14T10:00:02Z', totals: { tokens: 300, cost: '0.00' } },
  { traceId: 'c3', workflow: 'Hostile', status: 'running', startedAt: '2026-10-14T10:00:03Z', totals: { tokens: 1, cost: '0' },
    progress: { stage: '<img src=x onerror="window.__pwned=1">' }, signals: { toChild: 0, toParent: 0 } },
];

async function openWithChildren() {
  const { page, context, problems } = await openRun(browser, fixtures.base);
  await context.route(/\/api\/runs\/[^/?]+(\?.*)?$/, async (route) => {
    const res = await route.fetch();
    const body = await res.json();
    body.children = CHILDREN;
    await route.fulfill({ response: res, json: body });
  });
  page.on('pageerror', (e) => problems.push('pageerror: ' + e.message));
  await page.locator('#wtabs button.proc', { hasText: 'Runs' }).click();
  await page.locator('#rlrows tr.rl').first().click();
  await page.waitForSelector('#children:not([hidden])');
  return { page, context, problems };
}

async function rows(page) {
  return page.locator('#childrows tr.rl').evaluateAll((trs) => trs.map((tr) => Array.from(tr.children).map((td) => td.textContent.trim())));
}

test('a child row shows what it last reported and the signals each side has not read', { skip: skip || false }, async () => {
  const { page, context, problems } = await openWithChildren();
  const r = await rows(page);
  assert.equal(r.length, 3);
  assert.match(r[0].join(' | '), /Research/);
  assert.ok(r[0].includes('stage: reading · done: 1'), 'the report: ' + r[0]);
  assert.ok(r[0].includes('2 to it · 1 to you'), 'the unread signals: ' + r[0]);
  assert.deepEqual(problems, []);
  await context.close();
});

test('a child with no report and no signals draws as before, with a dash', { skip: skip || false }, async () => {
  const { page, context, problems } = await openWithChildren();
  const r = await rows(page);
  assert.equal(r[1][r[1].length - 2], '–');
  assert.equal(r[1][r[1].length - 1], '–');
  assert.deepEqual(problems, []);
  await context.close();
});

test('what a child reports is text: a tag in it is shown, never run', { skip: skip || false }, async () => {
  const { page, context } = await openWithChildren();
  const r = await rows(page);
  assert.ok(r[2].some((c) => c.includes('<img src=x')), 'the words are shown as they are');
  assert.equal(await page.locator('#childrows img').count(), 0, 'no element was made from them');
  assert.equal(await page.evaluate(() => window.__pwned), undefined, 'nothing ran');
  await context.close();
});

test('the table has the two new headings and the cost with the children is unchanged', { skip: skip || false }, async () => {
  const { page, context } = await openWithChildren();
  const heads = await page.locator('#children thead th').allInnerTexts();
  assert.deepEqual(heads.slice(-2).map((h) => h.trim().toLowerCase()), ['reported', 'unread signals']);
  assert.match(await page.locator('#childtotal').innerText(), /Cost with its children/);
  await context.close();
});
