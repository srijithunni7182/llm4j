// Browser smoke test for the interactive and static editions (needs Node and Playwright with Chromium).
//   node src/test/browser/smoke.js <report-dir>
// Visits every view, opens every case and change, fails on any script error, any network request,
// and any sign that text from the run was interpreted as markup (window.__pwned set by a hostile fixture).
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
(async () => {
  const dir = process.argv[2];
  const browser = await chromium.launch();
  const page = await browser.newPage();
  const errors = [], requests = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('request', r => { if (!/^(file|data|blob):/.test(r.url())) requests.push(r.url()); });
  await page.goto('file://' + dir + '/index.html');
  const views = ['overview', 'family/agents', 'dim/correctness', 'dim/grounding', 'compare', 'coverage', 'cost', 'models', 'traces', 'optimizer', 'notes'];
  for (const v of views) { await page.evaluate(x => { location.hash = x; }, '#' + v); await page.waitForTimeout(80); }
  for (const hash of ['#dim/grounding', '#compare']) {
    await page.evaluate(x => { location.hash = x; }, hash); await page.waitForTimeout(80);
    const rows = await page.$$('tbody tr.row');
    for (const r of rows) { await r.click(); await page.waitForTimeout(40); await page.keyboard.press('Escape'); }
  }
  const pwned = await page.evaluate(() => window.__pwned);
  await page.goto('file://' + dir + '/static/index.html');
  const staticPwned = await page.evaluate(() => window.__pwned);
  await browser.close();
  const problems = [...errors, ...requests.map(u => 'network request: ' + u)];
  if (pwned || staticPwned) problems.push('markup injection executed');
  if (problems.length) { console.error(problems.join('\n')); process.exit(1); }
  console.log('browser smoke test passed');
})();
