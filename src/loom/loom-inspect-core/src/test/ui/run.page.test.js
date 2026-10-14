'use strict';
/* R7 to R11, R14: the run page, in a real browser, over the simulator's scenarios. */
const test = require('node:test');
const assert = require('node:assert/strict');
const { missing, launch, startFixtures, openRun, openTab } = require('./support');

const SCENARIOS = ['context-fills', 'no-window', 'flaky-tool', 'task-diverted', 'task-broken', 'delegate-broken', 'trifecta', 'trifecta-accepted', 'injected-page', 'access-refused', 'truncated-answers'];
const skip = missing();
let fixtures; let browser;

test.before(async () => {
  if (skip) return;
  fixtures = await startFixtures(SCENARIOS);
  browser = await launch();
});
test.after(async () => {
  if (browser) await browser.close();
  if (fixtures) fixtures.stop();
});

async function text(page, sel) { return (await page.locator(sel).first().innerText()).replace(/\s+/g, ' ').trim(); }

test('a run broken by a task says which task, why, and where', { skip: skip || false }, async () => {
  const { page, context, problems } = await openRun(browser, fixtures.base, 'task-broken');
  const banner = page.locator('#banner');
  await banner.waitFor({ state: 'visible' });
  assert.match(await banner.getAttribute('class'), /broken/);
  const t = await text(page, '#banner');
  assert.match(t, /The run stopped/);
  assert.match(t, /task/);
  assert.match(t, /IssueRefund/);
  assert.match(t, /no on_failure/);
  assert.match(t, /Main\/s0|step/i);
  assert.deepEqual(problems, []);
  await context.close();
});

test('a run broken by a delegate names the delegate', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'delegate-broken');
  await page.locator('#banner.broken').waitFor();
  const t = await text(page, '#banner');
  assert.match(t, /delegate/);
  assert.match(t, /Writer/);
  await context.close();
});

test('a run that went through on_failure shows a quieter line, and a clean run shows no banner about failure', { skip: skip || false }, async () => {
  const a = await openRun(browser, fixtures.base, 'task-diverted');
  await a.page.locator('#banner.diverted').waitFor();
  assert.match(await text(a.page, '#banner'), /went through on_failure/);
  assert.doesNotMatch(await a.page.locator('#banner').getAttribute('class'), /broken/);
  await a.context.close();
  const b = await openRun(browser, fixtures.base, 'trifecta');
  const cls = await b.page.locator('#banner').getAttribute('class');
  assert.doesNotMatch(cls || '', /broken|diverted/);
  await b.context.close();
});

test('the Context and Tasks cards say what they should', { skip: skip || false }, async () => {
  const a = await openRun(browser, fixtures.base, 'context-fills');
  const ctx = await text(a.page, '#tile-context');
  assert.match(ctx, /context/i);
  assert.match(ctx, /\d+%/);
  assert.match(ctx, /Researcher/);
  assert.match(ctx, /compaction/);
  await a.context.close();
  const b = await openRun(browser, fixtures.base, 'task-diverted');
  const tasks = await text(b.page, '#tile-tasks');
  assert.match(tasks, /tasks/i);
  assert.match(tasks, /1 diverted/);
  assert.match(tasks, /1 failed/);
  await b.context.close();
  const c = await openRun(browser, fixtures.base, 'no-window');
  assert.match(await text(c.page, '#tile-context'), /no window/i);
  await c.context.close();
});

test('the context panel shows a meter per agent with the marks in force, and a timeline with a marker per compaction', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'context-fills');
  await openTab(page, 'Context');
  await page.locator('#contextpanel').waitFor({ state: 'visible' });
  const row = page.locator('#ctxrows .ctxrow', { hasText: 'Researcher' });
  const meter = row.locator('[role=meter]');
  const now = Number(await meter.getAttribute('aria-valuenow'));
  assert.ok(now > 0 && now <= 100, 'aria-valuenow is a percentage: ' + now);
  assert.equal(await meter.getAttribute('aria-valuemin'), '0');
  assert.equal(await meter.getAttribute('aria-valuemax'), '100');
  assert.match(await meter.getAttribute('aria-label'), /Researcher/);
  assert.equal(await row.locator('.mark.warn').count(), 1);
  assert.equal(await row.locator('.mark.high').count(), 1);
  assert.match(await row.locator('.mark.warn').getAttribute('style'), /85/);
  assert.match(await row.innerText(), /compactions?/);
  const markers = page.locator('#ctxline .event');
  assert.ok(await markers.count() >= 3, 'a marker for each of several compactions');
  assert.equal(await markers.first().getAttribute('tabindex'), '0');
  assert.match(await markers.first().getAttribute('aria-label'), /drop_oldest/);
  assert.ok(await page.locator('#ctxline .threshold').count() >= 2, 'the threshold lines are drawn');
  await page.locator('#ctxtable-details summary').click();
  assert.ok(await page.locator('#ctxtable tbody tr').count() >= 3, 'the same facts in a table');
  await context.close();
});

test('an agent with no known window gets a hatched bar and a hint, never a percentage', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'no-window');
  await openTab(page, 'Context');
  const row = page.locator('#ctxrows .ctxrow', { hasText: 'Reader' });
  await row.waitFor();
  assert.match(await row.getAttribute('class'), /nowindow/);
  assert.match(await row.innerText(), /no window known/);
  assert.equal(await row.locator('[role=meter]').count(), 0);
  assert.doesNotMatch(await row.innerText(), /\d+\s?%/);
  assert.ok(await page.locator('#ctxline circle.point, #ctxline polyline').count() > 0, 'the tokens are still drawn');
  await context.close();
});

test('the tools and tasks table puts the worst first and keeps a tool and a task apart', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'flaky-tool');
  await openTab(page, 'Tools');
  const rows = page.locator('#ttrows tr.tt');
  await rows.first().waitFor();
  assert.match(await rows.nth(0).innerText(), /Flaky/);
  assert.match(await rows.nth(0).innerText(), /failing/);
  assert.match(await rows.nth(1).innerText(), /Steady/);
  assert.match(await rows.nth(1).innerText(), /healthy/);
  assert.match(await rows.nth(0).innerText(), /timeout/);
  assert.match(await rows.nth(0).innerText(), /tool/);
  await context.close();
  const t = await openRun(browser, fixtures.base, 'task-broken');
  await openTab(t.page, 'Tools');
  const trow = t.page.locator('#ttrows tr.tt', { hasText: 'IssueRefund' });
  await trow.waitFor();
  assert.match(await trow.innerText(), /task/);
  assert.match(await trow.innerText(), /degraded/); // one task call that failed (after its retries): too few calls to say failing
  await t.context.close();
});

test('the filter chips show tools, tasks or both', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'task-diverted');
  await openTab(page, 'Tools');
  await page.locator('#ttrows tr.tt').first().waitFor();
  const all = await page.locator('#ttrows tr.tt').count();
  await page.locator('#ttfilter button', { hasText: 'tools' }).click();
  assert.equal(await page.locator('#ttrows tr.tt').count(), 0, 'this run has no tool');
  await page.locator('#ttfilter button', { hasText: 'tasks' }).click();
  assert.equal(await page.locator('#ttrows tr.tt').count(), all);
  assert.equal(await page.locator('#ttfilter button[aria-pressed=true]').innerText(), 'tasks');
  await context.close();
});

test('a row opens with Enter to show failures by category, the last ten outcomes and the latency histogram', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'flaky-tool');
  await openTab(page, 'Tools');
  const first = page.locator('#ttrows tr.tt').first();
  await first.focus();
  await page.waitForTimeout(2300); // the page redraws every second: a redraw must not take the focus away
  assert.equal(await page.evaluate(() => document.activeElement && document.activeElement.className), 'tt failing');
  assert.equal(await first.getAttribute('aria-expanded'), 'false');
  await page.keyboard.press('Enter');
  assert.equal(await first.getAttribute('aria-expanded'), 'true');
  const detail = page.locator('#ttrows tr.ttdetail').first();
  await detail.waitFor({ state: 'visible' });
  assert.match(await detail.innerText(), /timeout/);
  assert.equal(await detail.locator('.recent i').count(), 10);
  assert.ok(await detail.locator('.hist rect').count() > 3);
  assert.match(await detail.innerText(), /Researcher/);
  await page.keyboard.press('Enter');
  assert.equal(await first.getAttribute('aria-expanded'), 'false');
  await context.close();
});

test('the security tab shows what was exercised, accepted, suspected and refused', { skip: skip || false }, async () => {
  const t = await openRun(browser, fixtures.base, 'trifecta');
  await openTab(t.page, 'Security');
  const row = t.page.locator('#secagents tr', { hasText: 'Support' });
  await row.waitFor();
  assert.match(await row.innerText(), /exercised/);
  assert.match(await row.innerText(), /Fetch/);
  assert.match(await row.innerText(), /CustomerDB/);
  assert.match(await row.innerText(), /SendEmail/);
  assert.match(await text(t.page, '#sec-trifecta'), /1 agent/i);
  assert.match(await text(t.page, '#secstatement'), /cannot tell/i);
  await t.context.close();

  const a = await openRun(browser, fixtures.base, 'trifecta-accepted');
  await openTab(a.page, 'Security');
  const arow = a.page.locator('#secagents tr', { hasText: 'Support' });
  await arow.waitFor();
  assert.match(await arow.innerText(), /accepted/);
  assert.match(await arow.innerText(), /customer support: reads the page/);
  await a.context.close();

  const i = await openRun(browser, fixtures.base, 'injected-page');
  await openTab(i.page, 'Security');
  await i.page.locator('#secfindings .incident').first().waitFor();
  assert.match(await text(i.page, '#secfindings'), /Injection/);
  assert.match(await text(i.page, '#sec-injection'), /1 suspect/i);
  assert.doesNotMatch(await i.page.content(), /PLANTED-5A-MARKER/);
  assert.doesNotMatch(await i.page.locator('body').innerText(), /attacker@evil/);
  await i.context.close();

  const r = await openRun(browser, fixtures.base, 'access-refused');
  await openTab(r.page, 'Security');
  await r.page.locator('#secfindings .incident').first().waitFor();
  assert.match(await text(r.page, '#sec-access'), /3 refused/i);
  assert.match(await text(r.page, '#secfindings'), /Access/);
  await r.context.close();
});

test('the run tabs show the number of open security incidents', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'trifecta');
  const tab = page.locator('#runtabs button', { hasText: 'Security' });
  assert.match(await tab.innerText(), /Security\s*\D*1/);
  await context.close();
});

test('the incidents panel names the new kinds, and a broken run lists Broken first', { skip: skip || false }, async () => {
  const b = await openRun(browser, fixtures.base, 'task-broken');
  await b.page.locator('#inclist .incident').first().waitFor();
  assert.match(await b.page.locator('#inclist .incident').first().innerText(), /Broken/);
  await b.context.close();
  const d = await openRun(browser, fixtures.base, 'task-diverted');
  await d.page.locator('#inclist .incident').first().waitFor();
  assert.match(await text(d.page, '#inclist'), /Diverted/);
  await d.context.close();
  const t = await openRun(browser, fixtures.base, 'truncated-answers');
  await t.page.locator('#inclist .incident').first().waitFor();
  assert.match(await text(t.page, '#inclist'), /Truncated/);
  await t.context.close();
});

test('no console error, no failed request, in any scenario', { skip: skip || false }, async () => {
  for (const s of SCENARIOS) {
    const { page, context, problems } = await openRun(browser, fixtures.base, s);
    for (const tab of ['Overview', 'Context', 'Tools', 'Security']) { await openTab(page, tab); await page.waitForTimeout(50); }
    assert.deepEqual(problems, [], s);
    await context.close();
  }
});
