'use strict';
/* R20: run text is hostile. Markup, very long names, right-to-left text, emoji and control characters in tool names, error text and incident titles show as text and never become elements. */
const test = require('node:test');
const assert = require('node:assert/strict');
const { missing, launch, startFixtures, openRun, openTab } = require('./support');

const skip = missing();
let fixtures; let browser;
test.before(async () => { if (skip) return; fixtures = await startFixtures(['flaky-tool', 'task-broken', 'trifecta', 'injected-page', 'context-fills'], null, true); browser = await launch(); });
test.after(async () => { if (browser) await browser.close(); if (fixtures) fixtures.stop(); });

const MARKUP = '<img src=x onerror="window.__pwned=1"><script>window.__pwned=2</script>';
const NASTY = MARKUP + ' ‮evil‬ \u0007 \u0000 😀 ' + 'x'.repeat(400);
const KEYS = new Set(['name', 'title', 'detail', 'agent', 'text', 'reason', 'category', 'message', 'workflow', 'step', 'tool']);

function hostile(v, key) {
  if (Array.isArray(v)) return v.map((x) => hostile(x, key));
  if (v && typeof v === 'object') { const o = {}; for (const k of Object.keys(v)) o[k] = hostile(v[k], k); return o; }
  if (typeof v === 'string' && KEYS.has(key) && v.length) return NASTY;
  return v;
}

async function withHostileApi(page) {
  await page.route(/\/api\/(runs\/[^/]+|tools|security)(\?.*)?$/, async (route) => {
    const r = await route.fetch();
    const j = await r.json();
    await route.fulfill({ response: r, json: hostile(j) });
  });
}

async function nothingBecameMarkup(page) {
  const r = await page.evaluate(() => ({ pwned: window.__pwned, img: document.querySelectorAll('img[src="x"]').length, script: Array.from(document.querySelectorAll('script')).filter((s) => /__pwned/.test(s.textContent)).length, wide: document.documentElement.scrollWidth - window.innerWidth }));
  assert.equal(r.pwned, undefined, 'no script ran');
  assert.equal(r.img, 0, 'no element was made from the text');
  assert.equal(r.script, 0);
  assert.ok(r.wide <= 0, 'a long name did not widen the page by ' + r.wide);
}

test('R20: every run view shows hostile names and messages as text', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'task-broken');
  await withHostileApi(page);
  await page.waitForSelector('#main:not([hidden])');
  for (const t of ['Overview', 'Context', 'Tools', 'Security']) { await openTab(page, t); await page.waitForTimeout(1200); await nothingBecameMarkup(page); }
  await openTab(page, 'Tools');
  await page.locator('#ttrows tr.tt').first().click();
  assert.match(await page.locator('#main').innerText(), /<img src=x onerror=/, 'the markup is on the page as characters');
  await nothingBecameMarkup(page);
  await context.close();
});

test('R20: both fleet pages show hostile names and findings as text', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base);
  await withHostileApi(page);
  await page.locator('#wtabs button.proc', { hasText: 'Tools & tasks' }).click();
  await page.waitForSelector('#ftrows tr.tt');
  await page.locator('#ftrows tr.tt').first().click();
  await page.waitForTimeout(1200);
  assert.match(await page.locator('#fleettools').innerText(), /<img src=x onerror=/);
  await nothingBecameMarkup(page);
  await page.locator('#wtabs button.proc', { hasText: 'Security' }).click();
  await page.waitForSelector('#fsagents tr');
  await page.waitForTimeout(1200);
  assert.match(await page.locator('#fleetsec').innerText(), /<img src=x onerror=/);
  await nothingBecameMarkup(page);
  await context.close();
});
