'use strict';
/* R17 (as rendered), R19: roles and names, a keyboard-only walk, visible focus, a word beside every colour, in both themes. */
const test = require('node:test');
const assert = require('node:assert/strict');
const { missing, launch, startFixtures, openRun, openTab } = require('./support');

const SCENARIOS = ['context-fills', 'flaky-tool', 'task-broken', 'trifecta', 'trifecta-accepted', 'injected-page', 'access-refused', 'no-window'];
const skip = missing();
let fixtures; let browser;
test.before(async () => { if (skip) return; fixtures = await startFixtures(SCENARIOS, null, true); browser = await launch(); });
test.after(async () => { if (browser) await browser.close(); if (fixtures) fixtures.stop(); });

/** Every visible text element whose colour against the background it sits on is below the WCAG threshold, as the browser draws it. */
async function lowContrast(page) {
  return page.evaluate(() => {
    function parse(c) { const m = /rgba?\(([^)]+)\)/.exec(c); if (!m) return null; const p = m[1].split(',').map((x) => parseFloat(x)); return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 }; }
    function over(top, under) { const a = top.a; return { r: top.r * a + under.r * (1 - a), g: top.g * a + under.g * (1 - a), b: top.b * a + under.b * (1 - a), a: 1 }; }
    function lum(c) { const f = [c.r, c.g, c.b].map((v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }); return 0.2126 * f[0] + 0.7152 * f[1] + 0.0722 * f[2]; }
    function background(e) {
      const stack = [];
      for (let n = e; n; n = n.parentElement) { const b = parse(getComputedStyle(n).backgroundColor); if (b && b.a > 0) { stack.push(b); if (b.a === 1) break; } }
      let acc = parse(getComputedStyle(document.body).backgroundColor); if (acc.a < 1) acc = { r: 0, g: 0, b: 0, a: 1 };
      for (let i = stack.length - 1; i >= 0; i--) acc = over(stack[i], acc);
      return acc;
    }
    const bad = [];
    const all = document.querySelectorAll('body *');
    for (const e of all) {
      if (!e.offsetParent && getComputedStyle(e).position !== 'fixed') continue;
      const own = Array.from(e.childNodes).filter((n) => n.nodeType === 3 && n.textContent.trim()).length;
      if (!own) continue;
      const cs = getComputedStyle(e);
      if (cs.visibility === 'hidden' || parseFloat(cs.opacity) === 0) continue;
      const fg = parse(cs.color); const bg = background(e);
      const f = over(fg, bg);
      const l1 = lum(f); const l2 = lum(bg);
      const r = (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
      const size = parseFloat(cs.fontSize); const bold = parseInt(cs.fontWeight, 10) >= 700;
      const need = size >= 24 || (size >= 18.66 && bold) ? 3 : 4.5;
      if (r < need) bad.push((e.id || e.className || e.tagName) + ' "' + e.textContent.trim().slice(0, 24) + '" ' + r.toFixed(2));
    }
    return bad;
  });
}

for (const theme of ['dark', 'light']) {
  test('R17: as drawn in the ' + theme + ' theme, no visible text falls below its contrast threshold, on any page', { skip: skip || false }, async () => {
    const o = await openRun(browser, fixtures.base, 'trifecta');
    const { page, context } = o;
    if (theme === 'light') await page.click('#theme');
    const found = {};
    const look = async (label) => { found[label] = await lowContrast(page); };
    await page.waitForSelector('#runtabs .rt');
    for (const t of ['Overview', 'Context', 'Tools', 'Security']) { await openTab(page, t); await look('run ' + t); }
    await page.locator('#wtabs button.proc', { hasText: 'Tools & tasks' }).click(); await page.waitForSelector('#ftrows tr.tt'); await look('fleet tools');
    await page.locator('#ftrows tr.tt').first().click(); await look('fleet tools open');
    await page.locator('#wtabs button.proc', { hasText: 'Security' }).click(); await page.waitForSelector('#fsagents tr'); await look('fleet security');
    await page.locator('#wtabs button.proc', { hasText: 'Processes' }).click(); await look('processes');
    const flat = Object.entries(found).filter(([, v]) => v.length).map(([k, v]) => k + ': ' + Array.from(new Set(v)).slice(0, 6).join(' | '));
    assert.deepEqual(flat, []);
    await context.close();
  });
}

test('R19: tabs, chips, rows and markers are reachable and operable from the keyboard alone, and focus is visible', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'flaky-tool');
  await page.waitForSelector('#runtabs .rt');
  // the run's view tabs: Tab until the Tools & tasks one has focus, then Enter opens it
  let reached = false;
  for (let i = 0; i < 40 && !reached; i++) { await page.keyboard.press('Tab'); reached = await page.evaluate(() => document.activeElement && document.activeElement.classList.contains('rt') && /Tools/.test(document.activeElement.textContent)); }
  assert.ok(reached, 'the Tools & tasks view tab takes focus by Tab');
  const outline = await page.evaluate(() => { const s = getComputedStyle(document.activeElement); return s.outlineStyle + ' ' + s.outlineWidth; });
  assert.doesNotMatch(outline, /^none/, 'focus is visible: ' + outline);
  await page.keyboard.press('Enter');
  await page.waitForSelector('#toolspanel:not([hidden])');
  assert.equal(await page.locator('#runtabs .rt[aria-selected=true]').innerText().then((t) => /Tools/.test(t)), true);
  // a row opens with Enter and closes with Space
  const row = page.locator('#ttrows tr.tt').first();
  await row.focus();
  await page.keyboard.press('Enter');
  assert.equal(await page.locator('#ttrows tr.ttdetail').count(), 1);
  assert.equal(await row.getAttribute('aria-expanded'), 'true');
  await page.keyboard.press(' ');
  assert.equal(await page.locator('#ttrows tr.ttdetail').count(), 0);
  // the filter chips are buttons with a pressed state
  const chip = page.locator('#ttfilter button', { hasText: 'tools' }).first();
  await chip.focus(); await page.keyboard.press('Enter');
  assert.equal(await chip.getAttribute('aria-pressed'), 'true');
  await context.close();
});

test('R19: compaction markers take focus and carry their facts; the gauge is a meter; every table has headers', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base, 'context-fills');
  await openTab(page, 'Context');
  await page.waitForSelector('#ctxline .event');
  const marker = page.locator('#ctxline .event').first();
  await marker.focus();
  assert.equal(await page.evaluate(() => document.activeElement.classList.contains('event')), true);
  assert.match(await marker.getAttribute('aria-label'), /drop_oldest/);
  const m = page.locator('[role=meter]').first();
  for (const a of ['aria-valuenow', 'aria-valuemin', 'aria-valuemax']) assert.ok(await m.getAttribute(a) != null, a);
  assert.ok((await m.getAttribute('aria-label')) || (await m.getAttribute('aria-labelledby')), 'the meter has a name');
  const bare = await page.evaluate(() => Array.from(document.querySelectorAll('table')).filter((t) => t.offsetParent && !t.querySelector('th')).length);
  assert.equal(bare, 0, 'a visible table without header cells');
  assert.equal(await page.locator('nav#runtabs[role=tablist]').count(), 1);
  assert.equal(await page.locator('svg[role=img]').first().getAttribute('aria-labelledby') !== null || await page.locator('svg[role=img]').first().getAttribute('aria-label') !== null, true);
  await context.close();
});

test('R19: a word stands beside every colour: health, verdict, severity and incident state are all written out', { skip: skip || false }, async () => {
  const { page, context } = await openRun(browser, fixtures.base);
  await page.locator('#wtabs button.proc', { hasText: 'Tools & tasks' }).click();
  await page.waitForSelector('#ftrows tr.tt');
  for (const t of await page.locator('#ftrows .hw').allTextContents()) assert.match(t.trim(), /^(failing|degraded|healthy|unused)$/);
  await page.locator('#wtabs button.proc', { hasText: 'Security' }).click();
  await page.waitForSelector('#fsagents tr');
  for (const t of await page.locator('#fsagents .verdict').allTextContents()) assert.match(t.trim(), /^(exercised|accepted|armed|none|one|two)$/);
  for (const t of await page.locator('#fsfind .sev').allTextContents()) assert.match(t.trim(), /^(High|Warning|Info|Warn)$/i);
  await context.close();
});
