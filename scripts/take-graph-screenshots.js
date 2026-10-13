// Retakes the screenshots in docs/images/vscode-loom/ from the real panel (media/) and the laptop-doctor fixture.
// Run from the repository root after `npm ci && npx tsc -p tsconfig.test.json` in src/loom/vscode-loom:  node scripts/take-graph-screenshots.js
const path = require('path'), fs = require('fs');
const pw = require('playwright'); // npm i -g playwright (or in any parent folder)
const root = path.join(__dirname, '..', 'src', 'loom', 'vscode-loom'), out = path.join(__dirname, '..', 'docs', 'images', 'vscode-loom');
const PAGE = path.join(require('os').tmpdir(), 'loom-graph-shots.html');
const { renderPanelHtml } = require(root + '/out-test/src/graph/html.js');
const fx = JSON.parse(fs.readFileSync(root + '/test/fixtures/laptop-doctor.graph.json', 'utf8'));
const THEMES = {
  light: ['vscode-light', '--vscode-editor-background:#ffffff;--vscode-foreground:#3b3b3b;--vscode-descriptionForeground:#717171;--vscode-panel-border:#e5e5e5;--vscode-focusBorder:#5980a6;--vscode-sideBar-background:#f8f8f8;--vscode-font-family:Inter,"Segoe UI",system-ui,sans-serif'],
  dark: ['vscode-dark', '--vscode-editor-background:#1f1f1f;--vscode-foreground:#cccccc;--vscode-descriptionForeground:#9d9d9d;--vscode-panel-border:#2b2b2b;--vscode-focusBorder:#0078d4;--vscode-sideBar-background:#181818;--vscode-font-family:Inter,"Segoe UI",system-ui,sans-serif']
};
const watch = () => { const file = '/work/watcher/watch.loom'; return { type: 'graph', workflow: 'Watch', result: { version: 1, entry: file, files: [{ path: file, imports: [] }], runBudget: { tokens: 50000 }, agents: [{ name: 'Checker', model: 'gemini-2.5-flash', tools: ['ping', 'read_file'], approve: ['read_file'] }, { name: 'Notifier', model: 'gemini-2.5-flash', tools: ['send_email'], approve: ['send_email'] }], diagnostics: [], workflows: [{ name: 'Watch', file, line: 1, params: [], nodes: [
  { id: 'start', kind: 'start', label: 'Start' },
  { id: 'n1', kind: 'loop', label: 'loop forever', source: { file, line: 3 } },
  { id: 'n2', kind: 'delegate', label: 'delegate Checker', agent: 'Checker', parent: 'n1', branch: 'body', source: { file, line: 4 }, attrs: { variable: 'status', expecting: '{healthy}' } },
  { id: 'n3', kind: 'alt', label: 'status.healthy == no', parent: 'n1', branch: 'body', source: { file, line: 5 }, attrs: { condition: 'status.healthy == no' } },
  { id: 'n4', kind: 'delegate', label: 'delegate Notifier', agent: 'Notifier', parent: 'n3', branch: 'then', source: { file, line: 6 } },
  { id: 'n5', kind: 'sleep', label: 'sleep 60s', parent: 'n1', branch: 'body', source: { file, line: 8 } }],
  edges: [{ from: 'start', to: 'n1' }, { from: 'n1', to: 'n2' }, { from: 'n2', to: 'n3' }, { from: 'n3', to: 'n4', label: 'then' }, { from: 'n3', to: 'n5', label: 'else' }, { from: 'n4', to: 'n5' }, { from: 'n5', to: 'n1', label: 'again' }] }] } }; };
(async () => {
  fs.writeFileSync(PAGE, renderPanelHtml({ cspSource: 'file:', nonce: 'n', uri: (n) => 'file://' + path.join(root, 'media', n) }));
  const browser = await pw.chromium.launch();
  async function shot(name, theme, view, select, message, h) {
    const page = await (await browser.newContext({ viewport: { width: 1280, height: h || 760 }, deviceScaleFactor: 1.5 })).newPage();
    await page.addInitScript(() => { window.acquireVsCodeApi = () => ({ postMessage() {}, getState: () => null, setState() {} }); });
    await page.goto('file://' + PAGE);
    await page.evaluate(([c, v]) => { document.body.className = c; document.documentElement.style.cssText = v; }, THEMES[theme]);
    await page.evaluate((m) => window.postMessage(m, '*'), message);
    await page.waitForTimeout(150);
    await page.click('.vbtn[data-view=' + view + ']');
    await page.evaluate((id) => document.querySelector('#rail .seg[data-id="' + id + '"]').click(), select);
    await page.waitForTimeout(200);
    await page.screenshot({ path: `${out}/${name}.png` });
    await page.close();
  }
  const laptop = () => { const r = JSON.parse(JSON.stringify(fx.result)); r.agents.forEach((a) => { if (a.name === 'Diagnostician') { a.tools = ['Inspect', 'read_file']; a.approve = ['read_file']; } }); return { type: 'graph', workflow: 'Main', result: r, sections: { ['Main@' + r.workflows[0].file]: fx.sections } }; };
  await shot('graph-outline', 'light', 'outline', 'n11', laptop());
  await shot('graph-focus', 'light', 'focus', 'n11', laptop());
  await shot('graph-flow', 'light', 'flow', 'n11', laptop());
  await shot('graph-flow-dark', 'dark', 'flow', 'n2', laptop());
  await shot('graph-endless', 'dark', 'flow', 'n5', watch(), 560);
  await browser.close();
})();
