// Retakes the Loom Review panel screenshots in docs/images/vscode-loom/ from the real page (media/) and real engine output
// (src/loom/vscode-loom/test/fixtures/review/). Run from the repository root after `npm ci && npx tsc -p tsconfig.test.json` in
// src/loom/vscode-loom:  node scripts/take-review-screenshots.js
const path = require('path'), fs = require('fs');
const pw = require('playwright'); // npm i -g playwright (or in any parent folder)
const root = path.join(__dirname, '..', 'src', 'loom', 'vscode-loom'), out = path.join(__dirname, '..', 'docs', 'images', 'vscode-loom');
const PAGE = path.join(require('os').tmpdir(), 'loom-review-shots.html');
const { renderReviewHtml } = require(root + '/out-test/src/review/html.js');
const fixture = (name) => ({ review: JSON.parse(fs.readFileSync(`${root}/test/fixtures/review/${name}.review.json`, 'utf8')), graph: JSON.parse(fs.readFileSync(`${root}/test/fixtures/review/${name}.graph.json`, 'utf8')) });
const THEMES = {
  light: ['vscode-light', '--vscode-editor-background:#ffffff;--vscode-foreground:#3b3b3b;--vscode-focusBorder:#5980a6;--vscode-font-family:Inter,"Segoe UI",system-ui,sans-serif'],
  dark: ['vscode-dark', '--vscode-editor-background:#1f1f1f;--vscode-foreground:#cccccc;--vscode-focusBorder:#0078d4;--vscode-font-family:Inter,"Segoe UI",system-ui,sans-serif']
};
(async () => {
  fs.writeFileSync(PAGE, renderReviewHtml({ cspSource: 'file:', nonce: 'n', uri: (n) => 'file://' + path.join(root, 'media', n) }));
  const browser = await pw.chromium.launch();
  async function shot(name, theme, layout, fixtureName, select) {
    const page = await (await browser.newContext({ viewport: { width: 1280, height: 760 }, deviceScaleFactor: 1.5 })).newPage();
    await page.addInitScript(() => { window.acquireVsCodeApi = () => ({ postMessage() {}, getState: () => null, setState() {} }); });
    await page.goto('file://' + PAGE);
    await page.evaluate(([c, v]) => { document.body.className = c; document.documentElement.style.cssText = v; }, THEMES[theme]);
    const f = fixture(fixtureName);
    await page.evaluate((m) => window.postMessage(m, '*'), { type: 'review', review: f.review, graph: f.graph, status: 'idle', entry: `/project/${fixtureName}.loom` });
    await page.waitForTimeout(150);
    await page.click(`[data-layout="${layout}"]`);
    await page.waitForTimeout(100);
    if (select !== undefined) { await page.locator('#main [data-finding]').nth(select).click(); await page.waitForTimeout(100); }
    await page.screenshot({ path: `${out}/${name}.png` });
    await page.close();
  }
  await shot('review-split', 'light', 'split', 'risky', 1);
  await shot('review-split-dark', 'dark', 'split', 'risky', 1);
  await shot('review-outline', 'light', 'outline', 'risky', 0);
  await shot('review-list', 'light', 'list', 'risky', 2);
  await shot('review-list-dark', 'dark', 'list', 'risky', 2);
  await browser.close();
})();
