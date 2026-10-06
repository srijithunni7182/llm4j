'use strict';
/* Opens a report with many workflow graphs and times the first graph. Usage: node report-graph.perf.browser.js <index.html>
   Exits 0 when the first graph is drawn within 1.0 s of opening the report's Traces view, 77 without a browser. */
const path = require('path');
const { execSync } = require('child_process');

function loadPlaywright() {
  for (const resolve of [() => require('playwright'), () => require(path.join(execSync('npm root -g').toString().trim(), 'playwright'))]) {
    try { return resolve(); } catch (e) { /* next */ }
  }
  return null;
}

(async () => {
  const pw = loadPlaywright();
  if (!pw) { console.log('playwright is not installed'); process.exit(77); }
  let browser;
  try { browser = await pw.chromium.launch(); } catch (e) { console.log('no browser'); process.exit(77); }
  const file = 'file://' + path.resolve(process.argv[2]);
  const times = [];
  let cards = 0;
  for (let run = 0; run < 3; run++) {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const started = Date.now();
    await page.goto(file + '#traces');
    await page.waitForSelector('.lgcard .lg-node');
    await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
    times.push(Date.now() - started);
    cards = await page.locator('.lgcard').count();
    await page.close();
  }
  await browser.close();
  const worst = Math.max(...times);
  console.log(`cards ${cards}, first graph after ${times.join(', ')} ms (worst ${worst} ms)`);
  process.exit(cards === 20 && worst <= 1000 ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
