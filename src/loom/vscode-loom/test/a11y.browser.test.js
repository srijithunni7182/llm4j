'use strict';
/*
 * R29 and A10 / A16 of spec 10, in a real page: every control has a name, severity is never colour alone, text has enough contrast in
 * light, dark and high-contrast themes, keyboard focus is always visible and never trapped, nothing is cut off or needs sideways
 * scrolling at a 200 % zoom, forced colours and reduced motion are respected. Real engine output as data.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execSync } = require('child_process');
const { renderReviewHtml } = require('../out-test/src/review/html.js');

const MEDIA = path.join(__dirname, '..', 'media');
const FIX = path.join(__dirname, 'fixtures', 'review');
const fixture = (name) => ({ review: JSON.parse(fs.readFileSync(path.join(FIX, name + '.review.json'), 'utf8')), graph: JSON.parse(fs.readFileSync(path.join(FIX, name + '.graph.json'), 'utf8')) });
function loadPlaywright() {
  for (const resolve of [() => require('playwright'), () => require(path.join(execSync('npm root -g').toString().trim(), 'playwright'))]) { try { return resolve(); } catch (e) { /* next */ } }
  return null;
}
// the variables VS Code gives a page, per theme (the ones a theme sets; the page's own tokens come from these)
const THEMES = {
  light: { cls: 'vscode-light', vars: '--vscode-editor-background:#ffffff;--vscode-foreground:#3b3b3b;--vscode-font-family:sans-serif' },
  dark: { cls: 'vscode-dark', vars: '--vscode-editor-background:#1f1f1f;--vscode-foreground:#cccccc;--vscode-font-family:sans-serif' },
  hc: { cls: 'vscode-high-contrast', vars: '--vscode-editor-background:#000000;--vscode-foreground:#ffffff;--vscode-font-family:sans-serif' }
};

const pw = loadPlaywright();
let browser, htmlFile;
test.before(async () => {
  if (!pw) { return; }
  try { browser = await pw.chromium.launch(); } catch (e) { browser = null; }
  htmlFile = path.join(os.tmpdir(), 'loom-review-a11y-test.html');
  fs.writeFileSync(htmlFile, renderReviewHtml({ cspSource: 'file:', nonce: 'n', uri: (name) => 'file://' + path.join(MEDIA, name) }));
});
test.after(async () => { if (browser) { await browser.close(); } });
const skip = (t) => { if (!pw || !browser) { t.skip('playwright or a browser is not installed'); return true; } return false; };

async function open(theme, options = {}) {
  const context = await browser.newContext({ viewport: options.viewport || { width: 1440, height: 900 }, forcedColors: options.forcedColors, reducedMotion: options.reducedMotion });
  context.setDefaultTimeout(4000);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.addInitScript(() => { window.__posted = []; window.__state = null; window.acquireVsCodeApi = () => ({ postMessage: (m) => window.__posted.push(JSON.parse(JSON.stringify(m))), getState: () => window.__state, setState: (s) => { window.__state = JSON.parse(JSON.stringify(s)); } }); });
  await page.goto('file://' + htmlFile);
  const t = THEMES[theme];
  await page.evaluate(([cls, vars]) => { document.body.className = cls; document.documentElement.style.cssText = vars; }, [t.cls, t.vars]);
  return {
    page, errors,
    show: async (name, layout) => {
      const f = fixture(name);
      await page.evaluate((m) => window.postMessage(m, '*'), { type: 'review', review: f.review, graph: f.graph, status: 'idle', entry: '/p/' + name + '.loom' });
      await page.waitForTimeout(80);
      if (layout) { await page.click(`[data-layout="${layout}"]`); await page.waitForTimeout(60); }
    },
    close: () => context.close()
  };
}

// ---- in-page scans (functions run in the page)

function scanNames() {
  const missing = [];
  const named = (el) => {
    const label = el.getAttribute('aria-label') || el.getAttribute('title');
    if (label && label.trim()) { return true; }
    if (el.id && document.querySelector(`label[for="${el.id}"]`)) { return true; }
    if (el.getAttribute('aria-labelledby')) { return true; }
    return (el.innerText || el.textContent || '').trim().length > 0;
  };
  for (const el of document.querySelectorAll('button, input, select, textarea, a[href], [role=button], [role=checkbox], [role=tab], [role=listbox], [role=option], [tabindex]')) {
    if (el.hidden || el.closest('[hidden]')) { continue; }
    if (!named(el)) { missing.push(el.outerHTML.slice(0, 120)); }
  }
  return missing;
}

function scanContrast() {
  const parse = (c) => { const m = /rgba?\(([^)]+)\)/.exec(c); if (!m) { return null; } const p = m[1].split(/[ ,\/]+/).filter(Boolean).map(Number); return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 }; };
  const lum = (c) => { const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }; return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b); };
  const over = (top, under) => ({ r: top.r * top.a + under.r * (1 - top.a), g: top.g * top.a + under.g * (1 - top.a), b: top.b * top.a + under.b * (1 - top.a), a: 1 });
  const backgroundOf = (el) => {
    const stack = [];
    for (let n = el; n; n = n.parentElement) { const bg = parse(getComputedStyle(n).backgroundColor); if (bg && bg.a > 0) { stack.push(bg); if (bg.a === 1) { break; } } }
    let base = { r: 255, g: 255, b: 255, a: 1 };
    const body = parse(getComputedStyle(document.body).backgroundColor);
    if (body && body.a === 1 && !stack.length) { base = body; }
    for (const layer of stack.reverse()) { base = over(layer, base); }
    return base;
  };
  const low = [];
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  const seen = new Set();
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const el = node.parentElement;
    if (!node.textContent.trim() || !el || seen.has(el)) { continue; }
    seen.add(el);
    const style = getComputedStyle(el);
    if (style.visibility === 'hidden' || style.display === 'none' || el.closest('[hidden]') || el.getBoundingClientRect().width === 0) { continue; }
    const fg = parse(style.color);
    if (!fg) { continue; }
    const bg = backgroundOf(el);
    const text = over(fg, bg);
    const a = lum(text), b = lum(bg);
    const ratio = (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    const size = parseFloat(style.fontSize);
    const large = size >= 24 || (size >= 18.66 && parseInt(style.fontWeight, 10) >= 700);
    if (ratio < (large ? 3 : 4.5)) { low.push(`${ratio.toFixed(2)} ${el.tagName.toLowerCase()}.${el.className} "${node.textContent.trim().slice(0, 30)}"`); }
  }
  return low;
}

function scanOverflow() {
  const width = document.documentElement.clientWidth;
  const out = [];
  if (document.documentElement.scrollWidth > width + 1) { out.push(`the page is ${document.documentElement.scrollWidth} px wide in ${width}`); }
  for (const el of document.querySelectorAll('button, input, select, .vbtn, .btn')) {
    if (el.hidden || el.closest('[hidden]')) { continue; }
    const r = el.getBoundingClientRect();
    if (r.width === 0) { continue; }
    if (r.left < -1 || r.right > width + 1) { out.push(`${el.id || el.className} runs off the side: ${Math.round(r.left)}..${Math.round(r.right)} of ${width}`); }
  }
  return out;
}

// ---- tests

for (const theme of ['light', 'dark', 'hc']) {
  for (const layout of ['split', 'outline', 'list']) {
    test(`R29: every control has a name, and text has enough contrast (${theme}, ${layout})`, async (t) => {
      if (skip(t)) { return; }
      const p = await open(theme);
      await p.show('risky', layout);
      assert.deepEqual(await p.page.evaluate(scanNames), [], 'controls without a name');
      const low = await p.page.evaluate(scanContrast);
      assert.deepEqual(low, [], 'text under the contrast it needs');
      assert.deepEqual(p.errors, []);
      await p.close();
    });
  }
}

test('R29: severity is said in words, not by colour alone', async (t) => {
  if (skip(t)) { return; }
  const p = await open('light');
  await p.show('risky', 'split');
  const rows = await p.page.$$eval('#flist [data-finding]', (nodes) => nodes.map((n) => (n.getAttribute('aria-label') || '') + ' ' + n.innerText));
  assert.ok(rows.length > 3);
  for (const row of rows) { assert.match(row, /\b(high|medium|low|info)\b/i, row.slice(0, 80)); }
  for (const layout of ['outline', 'list']) {
    await p.page.click(`[data-layout="${layout}"]`);
    await p.page.waitForTimeout(60);
    const text = await p.page.innerText('#main');
    assert.match(text, /\b(high|medium|low)\b/i, layout);
  }
  await p.close();
});

test('A16: Tab moves through the whole page without a trap, and every stop shows where the focus is', async (t) => {
  if (skip(t)) { return; }
  for (const theme of ['light', 'dark', 'hc']) {
    const p = await open(theme);
    await p.show('risky', 'split');
    const stops = [];
    for (let i = 0; i < 60; i++) {
      await p.page.keyboard.press('Tab');
      const stop = await p.page.evaluate(() => {
        const el = document.activeElement;
        if (!el || el === document.body) { return null; }
        const s = getComputedStyle(el);
        const outline = s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0;
        const shadow = s.boxShadow && s.boxShadow !== 'none';
        const border = el.matches(':focus-visible') && (parseFloat(s.borderTopWidth) > 0);
        return { id: el.id || el.className || el.tagName, visible: outline || shadow || border, inView: el.getBoundingClientRect().width > 0 };
      });
      if (!stop) { break; } // focus left the page: not trapped
      stops.push(stop);
    }
    assert.ok(stops.length > 8, `${theme}: only ${stops.length} stops`);
    for (const stop of stops) {
      assert.ok(stop.inView, `${theme}: ${stop.id} has focus and no size`);
      assert.ok(stop.visible, `${theme}: nothing shows the focus on ${stop.id}`);
    }
    await p.close();
  }
});

test('A16: Shift+Tab goes back and Escape does not trap or lose the page', async (t) => {
  if (skip(t)) { return; }
  const p = await open('light');
  await p.show('risky', 'split');
  await p.page.keyboard.press('Tab');
  await p.page.keyboard.press('Tab');
  const second = await p.page.evaluate(() => document.activeElement.id || document.activeElement.className);
  await p.page.keyboard.press('Shift+Tab');
  const first = await p.page.evaluate(() => document.activeElement.id || document.activeElement.className);
  assert.notEqual(first, second);
  await p.page.keyboard.press('Escape');
  assert.ok(await p.page.evaluate(() => document.body.contains(document.activeElement)));
  await p.close();
});

for (const layout of ['split', 'outline', 'list']) {
  test(`A10: at 200 % zoom (a page half as wide) nothing needs sideways scrolling or is cut off (${layout})`, async (t) => {
    if (skip(t)) { return; }
    // 200 % zoom of a 1440 px panel is 720 CSS px; a docked panel is often narrower still
    for (const width of [720, 480, 360]) {
      const p = await open('light', { viewport: { width, height: 450 } });
      await p.show('risky', layout);
      assert.deepEqual(await p.page.evaluate(scanOverflow), [], `${layout} at ${width} px`);
      await p.close();
    }
  });
}

test('A10: a very long unbroken word in a finding does not push the page sideways', async (t) => {
  if (skip(t)) { return; }
  const p = await open('light', { viewport: { width: 520, height: 700 } });
  const f = fixture('risky');
  f.review.findings[0].title = 'x'.repeat(400);
  f.review.findings[0].why = 'y'.repeat(400) + '/' + 'z'.repeat(400);
  await p.page.evaluate((m) => window.postMessage(m, '*'), { type: 'review', review: f.review, graph: f.graph, status: 'idle', entry: '/p/risky.loom' });
  await p.page.waitForTimeout(100);
  assert.deepEqual(await p.page.evaluate(scanOverflow), []);
  await p.close();
});

test('A10: with forced colours (Windows high contrast) controls keep a border or outline so they can be seen', async (t) => {
  if (skip(t)) { return; }
  const p = await open('hc', { forcedColors: 'active' });
  await p.show('risky', 'split');
  const bare = await p.page.$$eval('button, input, select', (els) => els.filter((el) => !el.hidden && !el.closest('[hidden]') && el.getBoundingClientRect().width > 0).filter((el) => {
    const s = getComputedStyle(el);
    const underlined = s.textDecorationLine.includes('underline'); // a link-looking button is seen by its underline
    return !underlined && parseFloat(s.borderTopWidth) === 0 && parseFloat(s.borderBottomWidth) === 0 && !(s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0);
  }).map((el) => el.id || el.className));
  assert.deepEqual(bare, []);
  assert.deepEqual(await p.page.evaluate(scanNames), []);
  await p.close();
});

test('A10: with reduced motion nothing animates', async (t) => {
  if (skip(t)) { return; }
  const p = await open('light', { reducedMotion: 'reduce' });
  await p.show('risky', 'split');
  await p.page.click('#rerun');
  await p.page.waitForTimeout(100);
  const running = await p.page.evaluate(() => document.getAnimations().filter((a) => a.playState === 'running' && (a.effect && a.effect.getTiming().iterations === Infinity)).map((a) => a.animationName || a.id || 'animation'));
  assert.deepEqual(running, []);
  await p.close();
});

test('A16: changes are announced: the banner and the toasts are polite live regions', async (t) => {
  if (skip(t)) { return; }
  const p = await open('light');
  await p.show('risky', 'split');
  assert.equal(await p.page.getAttribute('#banner', 'role'), 'status');
  assert.equal(await p.page.getAttribute('#banner', 'aria-live'), 'polite');
  assert.equal(await p.page.getAttribute('#toasts', 'aria-live'), 'polite');
  // a stale result is said in the banner, as text
  await p.page.evaluate(() => window.postMessage({ type: 'status', status: 'stale' }, '*'));
  await p.page.waitForTimeout(60);
  const banner = await p.page.innerText('#banner').catch(() => '');
  assert.match(banner, /stale|edited|out of date|changed/i);
  await p.close();
});

test('A10: nothing in the summary strip spills out of its own cell, at common panel widths', async (t) => {
  if (skip(t)) { return; }
  for (const name of ['risky', 'priced', 'many']) {
    for (const width of [1440, 1280, 1000, 760, 480]) {
      const p = await open('light', { viewport: { width, height: 700 } });
      await p.show(name, 'split');
      const spills = await p.page.$$eval('#strip .stat', (cells) => cells.filter((c) => c.scrollWidth > c.clientWidth + 1).map((c) => `${c.innerText.replace(/\s+/g, ' ').slice(0, 40)} (${c.scrollWidth} in ${c.clientWidth})`));
      assert.deepEqual(spills, [], `${name} at ${width} px`);
      await p.close();
    }
  }
});
