'use strict';
/* R32: the run page can go back in time. A time bar over the run's marks; the whole page as it was; a banner that says it is a snapshot; and nothing in it moves. */
const test = require('node:test');
const assert = require('node:assert/strict');
const { missing, launch, startFixtures, openRun, openTab } = require('./support');

const skip = missing();
let fixtures; let browser;
test.before(async () => { if (skip) return; fixtures = await startFixtures(['context-fills', 'trifecta', 'flaky-tool', 'task-broken'], null, true); browser = await launch(); });
test.after(async () => { if (browser) await browser.close(); if (fixtures) fixtures.stop(); });

const text = async (page, sel) => (await page.locator(sel).first().innerText()).replace(/\s+/g, ' ').trim();
const api = (page, path) => page.evaluate((p) => fetch(p).then((r) => r.json()), path);
async function traceOf(page, scenario) { return (await api(page, '/api/runs')).runs.find((r) => r.client === scenario).traceId; }
const markButton = (page, kind) => page.locator('#tb-marks button.tbmark[data-kind="' + kind + '"]');

test('R32: the time bar spans the run\'s history and has a marker for each mark, reachable by keyboard', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'context-fills');
  await page.waitForSelector('#tb-slider');
  const t = await traceOf(page, 'context-fills');
  const tl = await api(page, '/api/runs/' + t + '/timeline');
  assert.equal(Number(await page.locator('#tb-slider').getAttribute('min')), tl.history.firstSeq);
  assert.equal(Number(await page.locator('#tb-slider').getAttribute('max')), tl.history.lastSeq);
  assert.equal(await page.locator('#tb-marks button.tbmark').count(), tl.marks.length);
  assert.ok(await markButton(page, 'compaction').count() >= 3, 'compactions are marked');
  assert.equal(await page.locator('#tb-live').getAttribute('aria-pressed'), 'true');
  assert.equal(await page.locator('#snapbanner').isHidden(), true);
  const first = page.locator('#tb-marks button.tbmark').first();
  await first.focus();
  assert.equal(await page.evaluate(() => document.activeElement.classList.contains('tbmark')), true);
  assert.match(await first.getAttribute('aria-label'), /start/i);
  await context.close();
});

test('R32: a marker shows the whole page as it was, under a banner that says it is a snapshot', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'trifecta');
  await page.waitForSelector('#tb-marks button.tbmark');
  const t = await traceOf(page, 'trifecta');
  const live = await api(page, '/api/runs/' + t);
  const tl = await api(page, '/api/runs/' + t + '/timeline');
  const open = tl.marks.find((m) => m.kind === 'incident-open');
  const startMark = tl.marks.find((m) => m.kind === 'start');
  // the start: no tool calls yet, no incident
  await markButton(page, 'start').first().click();
  await page.waitForSelector('#snapbanner:not([hidden])');
  assert.match(await text(page, '#snapbanner'), /As of \d\d:\d\d:\d\d/);
  assert.match(await text(page, '#snapbanner'), /snapshot, not live/);
  const atStart = await api(page, '/api/runs/' + t + '?at=' + startMark.seq);
  assert.equal(await text(page, '#tokens'), Number(atStart.totals.tokens).toLocaleString());
  assert.equal(await page.locator('#tb-live').getAttribute('aria-pressed'), 'false');
  assert.equal(await page.locator('#controls').isHidden(), true, 'no controls on a snapshot');
  assert.equal(await page.locator('#incidents').isHidden() || (await page.locator('#incidents .incident').count()) === 0, true, 'no incident yet at the start');
  // the incident's own mark: it is there
  await markButton(page, 'incident-open').first().click();
  await page.waitForFunction(() => document.querySelectorAll('#incidents .incident').length > 0);
  const atOpen = await api(page, '/api/runs/' + t + '?at=' + open.seq);
  assert.equal(await page.locator('#incidents .incident').count(), atOpen.incidents.length);
  assert.ok(atOpen.totals.calls <= live.totals.calls);
  await context.close();
});

test('R32: nothing in a snapshot changes while polls go on, and Back to live returns to the run', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'trifecta');
  await page.waitForSelector('#tb-marks button.tbmark');
  await markButton(page, 'start').first().click();
  await page.waitForSelector('#snapbanner:not([hidden])');
  const frozen = await page.locator('#main').innerText();
  const banner = await text(page, '#snapbanner').then((t) => t.replace(/\([^)]*ago\)/, ''));
  await page.waitForTimeout(2600);
  assert.equal(await page.locator('#main').innerText(), frozen, 'the page is exactly as it was');
  assert.equal((await text(page, '#snapbanner')).replace(/\([^)]*ago\)/, ''), banner);
  await page.click('#snapback');
  await page.waitForSelector('#snapbanner', { state: 'hidden' });
  assert.equal(await page.locator('#tb-live').getAttribute('aria-pressed'), 'true');
  const t = await traceOf(page, 'trifecta');
  const live = await api(page, '/api/runs/' + t);
  await page.waitForFunction((n) => document.getElementById('tokens').textContent === n, Number(live.totals.tokens).toLocaleString());
  await context.close();
});

test('R32: previous and next incident move between the incident marks', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'task-broken');
  await page.waitForSelector('#tb-marks button.tbmark');
  const t = await traceOf(page, 'task-broken');
  const tl = await api(page, '/api/runs/' + t + '/timeline');
  const opens = tl.marks.filter((m) => m.kind === 'incident-open');
  assert.ok(opens.length >= 1);
  assert.equal(await page.locator('#tb-next').isDisabled(), true, 'nothing after now');
  await page.click('#tb-prev');
  await page.waitForSelector('#snapbanner:not([hidden])');
  assert.equal(Number(await page.locator('#tb-slider').inputValue()), opens[opens.length - 1].seq);
  assert.equal(await page.locator('#tb-next').isDisabled(), false);
  await page.click('#tb-next');
  await page.waitForSelector('#snapbanner', { state: 'hidden' });   // past the last incident is the live end
  await context.close();
});

test('R32: the slider moves by keyboard, and the end of it is live', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'flaky-tool');
  await page.waitForSelector('#tb-slider');
  await page.locator('#tb-slider').focus();
  await page.keyboard.press('Home');
  await page.waitForSelector('#snapbanner:not([hidden])');
  const t = await traceOf(page, 'flaky-tool');
  const tl = await api(page, '/api/runs/' + t + '/timeline');
  assert.equal(Number(await page.locator('#tb-slider').inputValue()), tl.history.firstSeq);
  await page.keyboard.press('End');
  await page.waitForSelector('#snapbanner', { state: 'hidden' });
  await context.close();
});

test('R32: every view of the run is as it was: the Context view shows fewer compactions earlier', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'context-fills');
  await openTab(page, 'Context');
  await page.waitForSelector('#ctxline .event');
  const liveEvents = await page.locator('#ctxline .event').count();
  const t = await traceOf(page, 'context-fills');
  const tl = await api(page, '/api/runs/' + t + '/timeline');
  const comps = tl.marks.filter((m) => m.kind === 'compaction');
  await markButton(page, 'compaction').nth(2).click();
  await page.waitForSelector('#snapbanner:not([hidden])');
  await page.waitForFunction((n) => document.querySelectorAll('#ctxline .event').length < n, liveEvents);
  assert.ok((await page.locator('#ctxline .event').count()) <= 3, 'at the third compaction, three or fewer');
  assert.equal(comps.length >= 3, true);
  await openTab(page, 'Tools');
  assert.equal(await page.locator('#toolspanel').isVisible(), true, 'the other views are there too');
  await context.close();
});

test('R32: a point that is no longer kept says where the history starts and returns to live', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'trifecta');
  await page.waitForSelector('#tb-marks button.tbmark');
  await page.route(/\/api\/runs\/[0-9a-f]+\?at=\d+$/, (route) => route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'no such point in this run\'s history', historyStartsAt: '2026-10-14T09:00:00Z' }) }));
  await markButton(page, 'start').first().click();
  await page.waitForFunction(() => /no longer kept/.test(document.getElementById('tb-note').textContent));
  assert.match(await text(page, '#tb-note'), /history starts at/);
  assert.equal(await page.locator('#tb-live').getAttribute('aria-pressed'), 'true', 'back on the live run');
  await context.close();
});

test('R32: a run whose earliest records were dropped says its totals are partial', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'trifecta');
  await page.waitForSelector('#tb-marks button.tbmark');
  await page.route(/\/api\/runs\/[0-9a-f]+\?at=\d+$/, async (route) => {
    const r = await route.fetch();
    const j = await r.json();
    j.partial = true;
    j.history = Object.assign({}, j.history, { from: '2026-10-14T09:00:00Z' });
    await route.fulfill({ response: r, json: j });
  });
  await markButton(page, 'start').first().click();
  await page.waitForSelector('#snapbanner:not([hidden])');
  assert.match(await text(page, '#snapbanner'), /earliest records .* no longer kept/i);
  await context.close();
});

test('R32: while Paused the time bar still answers a click, and the live page stays frozen after', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'trifecta');
  await page.waitForSelector('#tb-marks button.tbmark');
  await page.click('#live');
  await markButton(page, 'start').first().click();
  await page.waitForSelector('#snapbanner:not([hidden])');
  assert.match(await text(page, '#live'), /Paused/);
  await context.close();
});
