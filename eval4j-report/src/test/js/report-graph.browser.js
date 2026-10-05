'use strict';
/*
 * Opens a generated eval4j report in a real browser and checks the workflow graph card: shapes and marks per
 * state, details, fallbacks, themes, hostile text, and that nothing is fetched. Usage: node report-graph.browser.js <index.html>
 * Exits 0 when every check passes, 1 when one fails (the failures are printed), 77 when no browser is available.
 */
const path = require('path');
const { execSync } = require('child_process');

function loadPlaywright() {
  for (const resolve of [() => require('playwright'), () => require(path.join(execSync('npm root -g').toString().trim(), 'playwright'))]) {
    try { return resolve(); } catch (e) { /* try the next place */ }
  }
  return null;
}

const results = [];
const check = (name, ok, detail) => results.push({ name, ok: !!ok, detail: ok ? '' : String(detail === undefined ? '' : detail) });

function luminance([r, g, b]) {
  const f = (c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}
function contrast(a, b) { const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); }
function parseColor(text) {
  let m = /rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)/.exec(text);
  if (m) { return [+m[1], +m[2], +m[3]]; }
  m = /color\(srgb ([\d.]+) ([\d.]+) ([\d.]+)/.exec(text);
  if (m) { return [+m[1] * 255, +m[2] * 255, +m[3] * 255]; }
  return null;
}

async function main() {
  const pw = loadPlaywright();
  if (!pw) { console.log('playwright is not installed'); process.exit(77); }
  let browser;
  try { browser = await pw.chromium.launch(); } catch (e) { console.log('no browser: ' + e.message.split('\n')[0]); process.exit(77); }
  const file = path.resolve(process.argv[2]);
  const url = 'file://' + file + '#traces';

  for (const scheme of ['light', 'dark']) {
    const context = await browser.newContext({ colorScheme: scheme, viewport: { width: 1280, height: 900 } });
    const outside = [];
    await context.route('**/*', (route) => { if (route.request().url().startsWith('file:')) { route.continue(); } else { outside.push(route.request().url()); route.abort(); } });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    page.on('console', (m) => { if (m.type() === 'error') { errors.push(m.text()); } });
    await page.goto(url);
    await page.waitForSelector('.lgcard .lg-node', { timeout: 15000 });
    await page.waitForTimeout(400);
    const tag = '[' + scheme + '] ';
    const card = (id) => page.locator('.card', { has: page.locator('h3', { hasText: id }) }).first();
    const states = (id, state) => card(id).locator('.lg-node.lg-state-' + state);

    check(tag + 'V10.8 nothing is fetched from outside the file', outside.length === 0, outside.join(', '));
    check(tag + 'no script errors on the page', errors.length === 0, errors.join(' | '));
    check(tag + 'V10.13 no Loom logo image in the report', (await page.locator('img').count()) === 0 && !(await page.content()).includes('loom_logo'));

    // V10.1 the graph card sits above the path rows, and the path rows are still there
    const order = await card('t_all_taken').evaluate((el) => {
      const kids = [...el.querySelectorAll('.lgcard, .path')];
      return kids.map((k) => k.className.split(' ')[0]);
    });
    check(tag + 'V10.1 graph card above the path rows', order[0] === 'lgcard' && order.filter((c) => c === 'path').length === 2, order.join(','));

    // V10.3a all taken
    check(tag + 'V10.3a every step on the path is marked as taken as expected', (await states('t_all_taken', 'ok').count()) === 6, await states('t_all_taken', 'ok').count());
    check(tag + 'V10.3a no missed or unexpected marks', (await states('t_all_taken', 'missed').count()) + (await states('t_all_taken', 'unexpected').count()) === 0);
    check(tag + 'V10.3a edges between consecutive steps are traversed', (await card('t_all_taken').locator('.lg-edge.lg-trav').count()) === 5, await card('t_all_taken').locator('.lg-edge.lg-trav').count());
    check(tag + 'V10.3a the mark for an expected step is a glyph', (await card('t_all_taken').locator('.lg-marktx', { hasText: '✓' }).count()) === 6);

    // V10.3b one missed
    const missed = states('t_one_missed', 'missed');
    check(tag + 'V10.3b the skipped steps are marked missed', (await missed.count()) === 2, await missed.count());
    check(tag + 'V10.3b missed has words and a dashed outline', (await card('t_one_missed').locator('.lg-marktx', { hasText: '✗ missed' }).count()) === 2 && (await missed.first().locator('.lg-shape').evaluate((e) => getComputedStyle(e).strokeDasharray)) !== 'none');
    check(tag + 'V10.3b edges to and from a missed step are not traversed', (await card('t_one_missed').locator('.lg-edge.lg-untrav[data-to="n3"]').count()) === 1);

    // V10.3c unexpected
    check(tag + 'V10.3c a step that was not expected is marked unexpected', (await states('t_one_unexpected', 'unexpected').count()) === 1 && (await card('t_one_unexpected').locator('.lg-marktx', { hasText: '! unexpected' }).count()) === 1);

    // V10.4 visits
    check(tag + 'V10.4 a step visited three times shows ×3', (await card('t_loop_x3').locator('.lg-chiptx', { hasText: '×3' }).count()) === 1);
    check(tag + 'V10.4 a step visited once shows no count', (await card('t_loop_x3').locator('.lg-chiptx', { hasText: '×1' }).count()) === 0);

    // V10.5 no expected path
    check(tag + 'V10.5 without an expected path there are only taken and not-visited steps', (await card('t_no_expected').locator('.lg-state-ok, .lg-state-missed, .lg-state-unexpected').count()) === 0 && (await states('t_no_expected', 'taken').count()) === 6);

    // V10.12 the note about the inferred path
    check(tag + 'V10.12 the path is said to be inferred', (await card('t_all_taken').getByText('Path is inferred from the order of delegations.').count()) === 1);

    // V10.15 fallbacks
    check(tag + 'V10.15 an empty path shows the expected steps as missed, the rest not visited, and says so', (await states('t_empty_path', 'missed').count()) === 3 && (await card('t_empty_path').locator('.lg-node.lg-dim').count()) === 4 && (await card('t_empty_path').getByText('No steps were recorded.').count()) === 1);
    check(tag + 'V10.15 a trace without a graph has no card', (await card('t_no_graph').locator('.lgcard').count()) === 0);

    // V10.16 unplaced events
    check(tag + 'V10.16 events that could not be placed are counted', (await card('t_unplaced').getByText('2 events could not be placed on a step.').count()) === 1);

    // V10.11a too large
    check(tag + 'V10.11a a graph over 500 nodes is not drawn and says why', (await card('t_too_large').locator('svg').count()) === 0 && (await card('t_too_large').getByText('Graph too large to draw (501 nodes).').count()) === 1 && (await card('t_too_large').locator('.path').count()) === 1);

    // V10.11b collapsed blocks
    const big = card('t_blocks_400');
    await big.scrollIntoViewIfNeeded();
    await page.waitForTimeout(300);
    const visible = await big.locator('.lg-node').count();
    check(tag + 'V10.11b a 400-node graph is folded to at most 300 nodes', visible > 0 && visible <= 300, visible);
    const toggles = await big.locator('.lg-chip-toggle').count();
    check(tag + 'V10.11b folded blocks show how many steps they hold', toggles > 0, toggles);
    if (toggles > 0) {
      // the whole 270-node graph is fitted into the view, so the chip is a few pixels wide: click its element, not a point
      await big.locator('.lg-chip-toggle').first().dispatchEvent('click');
      await page.waitForTimeout(200);
      check(tag + 'V10.11b expanding a block shows more steps', (await big.locator('.lg-node').count()) > visible);
    }

    // V10.7 details
    await card('t_all_taken').locator('.lg-node[data-id="n1"]').click();
    const side = card('t_all_taken').locator('.lg-side');
    const sideText = await side.innerText();
    check(tag + 'V10.7 selecting a step lists its events with time and type', (await side.locator('.lg-event').count()) === 3 && /delegate_start/.test(sideText) && /delegate_end/.test(sideText), sideText);
    check(tag + 'V10.7 duration is shown when there is a start and an end', /4\.20 s/.test(sideText), sideText);
    check(tag + 'V10.7 spend is labelled per agent', /Researcher \(per agent\): 890 tokens, 3 calls, \$0\.46/.test(sideText), sideText);
    check(tag + 'V10.7 settings are shown as chips', /retry 3/.test(sideText) && /timeout 1\.5m/.test(sideText) || /timeout 90s/.test(sideText), sideText);
    await card('t_all_taken').locator('.lg-node[data-id="n3"]').click();
    check(tag + 'V10.7 a step with no events says so', /No events were mapped to this step\./.test(await side.innerText()));

    // hostile text
    const evil = card('t_hostile');
    check(tag + 'hostile text is shown as text and runs nothing', (await page.evaluate(() => window.__pwned)) === undefined && (await evil.locator('img').count()) === 0 && (await evil.locator('.lg-node').count()) === 4);

    // V10.6 colours: marks and node text stay readable
    const pairs = await page.evaluate(() => {
      const out = [];
      document.querySelectorAll('.lgcard .lg-node').forEach((g) => {
        const shape = g.querySelector('.lg-shape'), title = g.querySelector('.lg-title');
        if (shape && title) { out.push(['node', getComputedStyle(shape).fill, getComputedStyle(title).fill, g.classList.contains('lg-dim')]); }
        const box = g.querySelector('.lg-markbox'), tx = g.querySelector('.lg-marktx');
        if (box && tx) { out.push(['mark', getComputedStyle(box).fill, getComputedStyle(tx).fill, false]); }
      });
      const surface = getComputedStyle(document.querySelector('.lg-canvas')).backgroundColor;
      return { pairs: out, surface };
    });
    const bad = [];
    pairs.pairs.forEach(([what, a, b, dim]) => {
      const fa = parseColor(a), fb = parseColor(b);
      if (!fa || !fb || dim) { return; }
      const ratio = contrast(fa, fb);
      if (ratio < 4.5) { bad.push(what + ' ' + a + ' / ' + b + ' = ' + ratio.toFixed(2)); }
    });
    check(tag + 'V10.6 node text and mark text have at least 4.5:1 contrast', bad.length === 0 && pairs.pairs.length > 20, bad.slice(0, 4).join('; ') || pairs.pairs.length);
    // marks have text or a glyph, so states are identifiable without colour
    check(tag + 'V10.6 every marked step carries a glyph or words', await page.evaluate(() => [...document.querySelectorAll('.lgcard .lg-node[class*="lg-state-"]:not(.lg-state-none)')].every((g) => (g.querySelector('.lg-marktx') || {}).textContent)));

    // V10.2 / keyboard: nodes are buttons reachable by Tab
    await card('t_all_taken').locator('.lg-node[data-id="start"]').focus();
    await page.keyboard.press('Enter');
    check(tag + 'steps can be selected with the keyboard', /Kind/.test(await card('t_all_taken').locator('.lg-side').innerText()));
    await context.close();
  }
  await browser.close();
  const failed = results.filter((r) => !r.ok);
  results.forEach((r) => console.log((r.ok ? 'PASS ' : 'FAIL ') + r.name + (r.detail ? '  -> ' + r.detail : '')));
  console.log(`${results.length - failed.length}/${results.length} checks passed`);
  process.exit(failed.length ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(1); });
