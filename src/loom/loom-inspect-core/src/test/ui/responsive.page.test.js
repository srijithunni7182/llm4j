'use strict';
/* R18: at 360, 768 and 1360 px nothing makes the page scroll sideways; the tab row wraps; tables scroll inside their own box. */
const test = require('node:test');
const assert = require('node:assert/strict');
const { missing, launch, startFixtures, startFabric, openRun, openTab } = require('./support');

const skip = missing();
let fixtures; let fabric; let browser;
test.before(async () => {
  if (skip) return;
  fixtures = await startFixtures(['context-fills', 'flaky-tool', 'task-broken', 'trifecta', 'trifecta-accepted', 'injected-page', 'access-refused'], null, true);
  fabric = await startFabric();
  browser = await launch();
});
test.after(async () => { if (browser) await browser.close(); if (fixtures) fixtures.stop(); if (fabric) fabric.stop(); });

const noSideways = (page) => page.evaluate(() => ({ doc: document.documentElement.scrollWidth, win: window.innerWidth }));

for (const width of [360, 768, 1360]) {
  test('R18: at ' + width + ' px no page scrolls sideways', { skip: skip || false }, async () => {
    const { page, context } = await openRun(browser, fixtures.base, 'trifecta', { width, height: 800 });
    await page.waitForSelector('#runtabs .rt');
    const seen = [];
    for (const t of ['Overview', 'Context', 'Tools', 'Security']) {
      await openTab(page, t);
      const w = await noSideways(page);
      if (w.doc > w.win) seen.push('run ' + t + ' ' + w.doc + '>' + w.win);
    }
    for (const [tab, sel] of [['Tools & tasks', '#ftrows tr.tt'], ['Security', '#fsagents tr'], ['Processes', '#proctable']]) {
      await page.locator('#wtabs button.proc', { hasText: tab }).click();
      await page.waitForSelector(sel, { state: 'attached' });
      const w = await noSideways(page);
      if (w.doc > w.win) seen.push(tab + ' ' + w.doc + '>' + w.win);
    }
    assert.deepEqual(seen, []);
    // the tab row wraps rather than overflowing
    const tabs = await page.locator('#wtabs').evaluate((n) => ({ sw: n.scrollWidth, cw: n.clientWidth }));
    assert.ok(tabs.sw <= tabs.cw + 1, 'the workflow tabs wrap');
    await context.close();
  });
}

test('R18: at 360 px a wide table scrolls inside its own box', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, null, { width: 360, height: 800 });
  await page.locator('#wtabs button.proc', { hasText: 'Tools & tasks' }).click();
  await page.waitForSelector('#ftrows tr.tt');
  const box = await page.locator('#fleettools .tbl-wrap').first().evaluate((n) => ({ sw: n.scrollWidth, cw: n.clientWidth, ox: getComputedStyle(n).overflowX }));
  assert.ok(box.sw > box.cw, 'the table is wider than the phone');
  assert.match(box.ox, /auto|scroll/);
  await context.close();
});

test('R18: the Configuration page fits at 360 px too', { skip: skip || false }, async () => {
  const context = await browser.newContext({ viewport: { width: 360, height: 800 } });
  const page = await context.newPage();
  await page.goto(fabric.base);
  await page.fill('#pw', fabric.password);
  await page.locator('#loginform button[type=submit]').click();
  await page.locator('#wtabs button.proc', { hasText: 'Configuration' }).click();
  await page.waitForFunction(() => document.querySelectorAll('#rb-rule option').length > 0);
  const w = await noSideways(page);
  assert.ok(w.doc <= w.win, 'configuration: ' + w.doc + ' > ' + w.win);
  await context.close();
});
