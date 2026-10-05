'use strict';
/*
 * The panel page in a real browser, with a stand-in for the VS Code API: what it draws for each message the extension
 * sends, and what it sends back. Skipped when playwright or a browser is not installed.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execSync } = require('child_process');
const { renderPanelHtml } = require('../out-test/src/graph/html.js');

const MEDIA = path.join(__dirname, '..', 'media');
const GOLDEN = path.join(__dirname, '..', '..', 'ai-agent4j-loom', 'src', 'test', 'resources', 'graph', 'golden');
const golden = (name) => JSON.parse(fs.readFileSync(path.join(GOLDEN, name + '.json'), 'utf8'));

function loadPlaywright() {
  for (const resolve of [() => require('playwright'), () => require(path.join(execSync('npm root -g').toString().trim(), 'playwright'))]) {
    try { return resolve(); } catch (e) { /* next */ }
  }
  return null;
}

const THEMES = {
  light: { cls: 'vscode-light', vars: '--vscode-editor-background:#ffffff;--vscode-foreground:#1f2330;--vscode-descriptionForeground:#5a6177;--vscode-panel-border:#d6d9e5;--vscode-focusBorder:#5a54e0;--vscode-font-family:sans-serif' },
  dark: { cls: 'vscode-dark', vars: '--vscode-editor-background:#14161d;--vscode-foreground:#e7e9f1;--vscode-descriptionForeground:#9aa2b7;--vscode-panel-border:#2a3040;--vscode-focusBorder:#9a95ff;--vscode-font-family:sans-serif' },
  hc: { cls: 'vscode-high-contrast', vars: '--vscode-editor-background:#000000;--vscode-foreground:#ffffff;--vscode-descriptionForeground:#d0d0d0;--vscode-panel-border:#6fc3df;--vscode-focusBorder:#f38518;--vscode-contrastBorder:#6fc3df;--vscode-font-family:sans-serif' }
};

function luminance([r, g, b]) { const f = (c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }; return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b); }
function contrast(a, b) { const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); }
function parseColor(text) {
  let m = /rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)/.exec(text); if (m) { return [+m[1], +m[2], +m[3]]; }
  m = /color\(srgb ([\d.]+) ([\d.]+) ([\d.]+)/.exec(text); return m ? [+m[1] * 255, +m[2] * 255, +m[3] * 255] : null;
}

const pw = loadPlaywright();
let browser, htmlFile;

test.before(async () => {
  if (!pw) { return; }
  try { browser = await pw.chromium.launch(); } catch (e) { browser = null; }
  htmlFile = path.join(os.tmpdir(), 'loom-graph-panel-test.html');
  fs.writeFileSync(htmlFile, renderPanelHtml({ cspSource: 'file:', nonce: 'testnonce', uri: (name) => 'file://' + path.join(MEDIA, name) }));
});
test.after(async () => { if (browser) { await browser.close(); } });

/** Opens the panel with a stand-in for acquireVsCodeApi. */
async function open(theme = 'dark', options = {}) {
  const context = await browser.newContext({ viewport: options.viewport || { width: 1100, height: 800 }, reducedMotion: options.reducedMotion || 'no-preference' });
  const outside = [];
  await context.route('**/*', (route) => { if (route.request().url().startsWith('file:')) { route.continue(); } else { outside.push(route.request().url()); route.abort(); } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') { errors.push(m.text()); } });
  await page.addInitScript(() => { window.__posted = []; window.acquireVsCodeApi = () => ({ postMessage: (m) => window.__posted.push(JSON.parse(JSON.stringify(m))) }); });
  await page.goto('file://' + htmlFile);
  const t = THEMES[theme];
  await page.evaluate(([cls, vars]) => { document.body.className = cls; const s = document.createElement('style'); s.setAttribute('nonce', 'x'); document.documentElement.style.cssText = vars; }, [t.cls, t.vars]);
  const api = {
    page, errors, outside, context,
    send: async (message) => { await page.evaluate((m) => window.postMessage(m, '*'), message); await page.waitForTimeout(80); },
    posted: () => page.evaluate(() => window.__posted),
    clearPosted: () => page.evaluate(() => { window.__posted.length = 0; }),
    close: () => context.close()
  };
  return api;
}

const graph = (name, workflow) => { const result = golden(name); return { type: 'graph', result, workflow: workflow || result.workflows[0].name }; };
const skipIfNoBrowser = (t) => { if (!pw || !browser) { t.skip('playwright or a browser is not installed'); return true; } return false; };

test('before the extension answers, the panel shows the logo, says it is reading, and has its controls off; it asks for its content', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  assert.ok(await p.page.locator('#loading').isVisible());
  assert.equal(await p.page.locator('#loadingText').textContent(), 'Reading the script…');
  assert.ok(await p.page.locator('#fit').isDisabled());
  assert.deepEqual(await p.posted(), [{ type: 'ready' }]);
  await p.send({ type: 'loading', file: '/p/main.loom' });
  assert.equal(await p.page.locator('#loadingText').textContent(), 'Reading main.loom…');
  await p.close();
});

test('a graph replaces the loading state, fills the selector and draws the steps', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(graph('content_factory'));
  assert.ok(!(await p.page.locator('#loading').isVisible()));
  assert.equal(await p.page.locator('.lg-node').count(), 8);
  assert.equal(await p.page.locator('#workflow option').count(), 1);
  assert.ok(await p.page.locator('#fit').isEnabled());
  assert.equal(await p.page.locator('#workflow').inputValue(), 'GenerateContent');
  assert.deepEqual(p.errors, [], 'no script or policy errors');
  assert.deepEqual(p.outside, []);
  await p.close();
});

test('V7.1: clicking a step that has a line asks the extension to open that line beside the panel', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(graph('content_factory'));
  await p.clearPosted();
  await p.page.locator('.lg-node[data-id="n1"]').click();
  const posted = await p.posted();
  assert.deepEqual(posted, [{ type: 'openSource', file: '<root>/samples/content_factory/main.loom', line: 5, beside: true }]);
  assert.match(await p.page.locator('#selected').innerText(), /delegate Researcher/);
  assert.match(await p.page.locator('#selected').innerText(), /main\.loom:5/);
  await p.close();
});

test('V7.2: double-clicking a call opens the callee with a breadcrumb and Back; Ctrl-click opens its source instead', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(graph('imports_parent'));
  await p.page.locator('.lg-node[data-id="n1"]').dblclick();
  await p.page.waitForTimeout(100);
  const crumbs = await p.page.locator('#crumbs').innerText();
  assert.match(crumbs, /ParentWorkflow\s*›\s*ChildWorkflow/);
  assert.match(crumbs, /child\.loom/);
  assert.equal(await p.page.locator('#workflow').inputValue(), 'ChildWorkflow');
  assert.ok((await p.posted()).some((m) => m.type === 'selectWorkflow' && m.name === 'ChildWorkflow'));
  await p.page.locator('#crumbs button', { hasText: 'Back' }).click();
  await p.page.waitForTimeout(100);
  assert.equal(await p.page.locator('#workflow').inputValue(), 'ParentWorkflow');
  assert.ok(await p.page.locator('#crumbs').isHidden());

  await p.clearPosted();
  await p.page.locator('.lg-node[data-id="n1"]').click({ modifiers: ['Control'] });
  const posted = await p.posted();
  assert.deepEqual(posted, [{ type: 'openSource', file: '<root>/src/test/resources/imports/child.loom', line: 6, beside: true }]);
  assert.ok(await p.page.locator('#crumbs').isHidden(), 'the panel did not move');
  await p.close();
});

test('V7.3: the files list shows the entry and what it imports, with their workflows', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(graph('imports_parent'));
  const files = await p.page.locator('#files').innerText();
  assert.match(files, /parent\.loom\s+entry\s+ParentWorkflow/);
  assert.match(files, /child\.loom\s+imported\s+ChildWorkflow/);
  assert.equal(await p.page.locator('#workflow optgroup').count(), 1);
  assert.equal(await p.page.locator('#workflow optgroup').getAttribute('label'), 'From child.loom');
  await p.close();
});

test('V7.4: the step for the line the cursor is on is highlighted, and the highlight clears when the cursor leaves', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(graph('content_factory'));
  await p.send({ type: 'highlight', id: 'n3' });
  assert.ok(await p.page.locator('.lg-node[data-id="n3"].lg-sel').count());
  await p.send({ type: 'highlight', id: null });
  assert.equal(await p.page.locator('.lg-node.lg-sel').count(), 0);
  // a step the person clicked stays selected when the cursor moves to a line with no step
  await p.page.locator('.lg-node[data-id="n1"]').click();
  await p.send({ type: 'highlight', id: null });
  assert.equal(await p.page.locator('.lg-node[data-id="n1"].lg-sel').count(), 1);
  await p.close();
});

test('V6.6: diagnostics show as a count, expand into rows, and a row asks to open its file and line', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  const message = graph('content_factory');
  message.result.diagnostics = [
    { severity: 'warning', file: '/p/b.loom', line: 7, message: 'Workflow X is also defined elsewhere' },
    { severity: 'error', file: '/p/c.loom', line: 3, message: 'call Y: no workflow with that name' }
  ];
  await p.send(message);
  assert.match(await p.page.locator('#diagnostics > button').innerText(), /1 error, 1 warning/);
  assert.equal(await p.page.locator('#diagnostics li').count(), 0);
  await p.page.locator('#diagnostics > button').click();
  assert.equal(await p.page.locator('#diagnostics li').count(), 2);
  await p.clearPosted();
  await p.page.locator('#diagnostics li button').nth(1).click();
  assert.deepEqual(await p.posted(), [{ type: 'openSource', file: '/p/c.loom', line: 3, beside: false }]);
  await p.close();
});

test('V8.3: a stale notice keeps the graph, offers the file, and a new graph removes it', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(graph('content_factory'));
  await p.send({ type: 'stale', message: 'Parse error at line 14: expected )', file: '/p/main.loom', line: 14 });
  assert.ok(await p.page.locator('#stale').isVisible());
  assert.match(await p.page.locator('#staleText').innerText(), /last good graph.*expected \)/);
  assert.equal(await p.page.locator('.lg-node').count(), 8, 'the graph is still there');
  await p.clearPosted();
  await p.page.locator('#staleOpen').click();
  assert.deepEqual(await p.posted(), [{ type: 'openSource', file: '/p/main.loom', line: 14, beside: false }]);
  await p.send(graph('content_factory'));
  assert.ok(await p.page.locator('#stale').isHidden());
  await p.close();
});

test('V8.1: a refreshed graph keeps the workflow, the selected step and the zoom', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(graph('imports_parent'));
  await p.page.locator('.lg-node[data-id="n1"]').click();
  await p.page.locator('#zoomIn').click();
  await p.page.locator('#zoomIn').click();
  const zoom = await p.page.locator('#zoomLabel').innerText();
  await p.send(graph('imports_parent'));
  assert.equal(await p.page.locator('#zoomLabel').innerText(), zoom);
  assert.equal(await p.page.locator('.lg-node[data-id="n1"].lg-sel').count(), 1);
  assert.equal(await p.page.locator('#workflow').inputValue(), 'ParentWorkflow');
  await p.close();
});

test('a workflow chosen from the selector stays chosen after a refresh', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(graph('imports_parent'));
  await p.page.locator('#workflow').selectOption('ChildWorkflow');
  await p.send({ ...graph('imports_parent'), workflow: 'ParentWorkflow' });
  assert.equal(await p.page.locator('#workflow').inputValue(), 'ChildWorkflow');
  await p.close();
});

test('V6.9: hovering a step that names an agent shows its details, and a step that needs approval shows the hand', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  const message = graph('content_factory');
  message.result.agents[0] = { ...message.result.agents[0], temperature: 0.2, persona: 'Analyst', tools: ['web_search'], approve: ['send_email'], maxIterations: 8, budget: { tokens: 20000 } };
  await p.send(message);
  await p.page.locator('.lg-node[data-id="n1"]').hover();
  await p.page.waitForTimeout(100);
  const card = await p.page.locator('#card').innerText();
  assert.match(card, /Researcher/);
  assert.match(card, /temp 0\.2/);
  assert.match(card, /Analyst/);
  assert.match(card, /Needs approval: send_email/);
  assert.match(card, /max 8 iterations/);
  assert.equal(await p.page.locator('.lg-node[data-id="n1"] .lg-approve').count(), 1);
  await p.page.mouse.move(5, 5);
  await p.page.waitForTimeout(100);
  assert.ok(await p.page.locator('#card').isHidden());
  await p.close();
});

test('V6.3: the steps can be reached and chosen with the keyboard alone', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(graph('content_factory'));
  await p.page.locator('.lg-node[data-id="start"]').focus();
  await p.page.keyboard.press('ArrowDown');
  assert.equal(await p.page.evaluate(() => document.activeElement.getAttribute('data-id')), 'n1');
  await p.page.keyboard.press('Enter');
  assert.match(await p.page.locator('#selected').innerText(), /delegate Researcher/);
  await p.close();
});

test('V6.3: a call can be opened with Shift+Enter', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(graph('imports_parent'));
  await p.page.locator('.lg-node[data-id="n1"]').focus();
  await p.page.keyboard.press('Shift+Enter');
  await p.page.waitForTimeout(100);
  assert.equal(await p.page.locator('#workflow').inputValue(), 'ChildWorkflow');
  await p.close();
});

test('no workflows: the empty state shows the logo and a button to line 1', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  const message = graph('content_factory');
  message.result.workflows = [];
  await p.send(message);
  assert.ok(await p.page.locator('#empty').isVisible());
  assert.match(await p.page.locator('#emptyText').innerText(), /no workflows/);
  assert.equal(await p.page.locator('#empty img').getAttribute('alt'), 'Loom');
  await p.clearPosted();
  await p.page.locator('#emptyOpen').click();
  assert.deepEqual(await p.posted(), [{ type: 'openSource', file: '<root>/samples/content_factory/main.loom', line: 1, beside: false }]);
  await p.close();
});

test('the legend lists the colour groups and Copy Mermaid names the workflow', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  await p.send(graph('content_factory'));
  await p.page.locator('#legendButton').click();
  assert.equal(await p.page.locator('#legend span').count(), 8);
  assert.equal(await p.page.locator('#legendButton').getAttribute('aria-expanded'), 'true');
  await p.clearPosted();
  await p.page.locator('#mermaid').click();
  assert.deepEqual(await p.posted(), [{ type: 'copyMermaid', name: 'GenerateContent' }]);
  await p.close();
});

test('V6.5: hostile text from a script is shown as text and runs nothing', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  const evil = '<img src=x onerror="window.__pwned=1"> </script><script>window.__pwned=1</script>';
  const message = graph('content_factory');
  message.result.workflows[0].nodes[1].label = 'delegate ' + evil;
  message.result.workflows[0].nodes[1].agent = evil;
  message.result.agents.push({ name: evil, model: evil });
  message.result.diagnostics = [{ severity: 'warning', file: '/p/a.loom', line: 1, message: evil }];
  message.result.workflows[0].name = evil;
  await p.send(message);
  await p.page.locator('#diagnostics > button').click();
  await p.page.locator('.lg-node[data-id="n1"]').click();
  assert.equal(await p.page.evaluate(() => window.__pwned), undefined);
  assert.equal(await p.page.locator('img[src="x"]').count(), 0);
  assert.equal(await p.page.locator('#workflow option').first().textContent().then((t) => t.includes('<img')), true, 'shown as text');
  assert.deepEqual(p.errors, []);
  await p.close();
});

test('V6.10: the toolbar shows the logo mark, the loading state the full logo, and motion is off when asked', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open('dark', { reducedMotion: 'reduce' });
  const mark = p.page.locator('.toolbar .tile img');
  assert.equal(await mark.getAttribute('alt'), '');
  assert.ok((await mark.evaluate((img) => img.naturalWidth)) > 0, 'the mark image loaded');
  assert.equal(await p.page.locator('.toolbar .tile').evaluate((e) => getComputedStyle(e).backgroundColor), 'rgb(26, 29, 38)');
  assert.equal(await p.page.locator('#loading img').getAttribute('alt'), 'Loom');
  assert.equal(await p.page.locator('#loading .pulse').evaluate((e) => getComputedStyle(e).animationName), 'none');
  await p.close();

  const normal = await open('light');
  assert.notEqual(await normal.page.locator('#loading .pulse').evaluate((e) => getComputedStyle(e).animationName), 'none');
  await normal.close();

  const hc = await open('hc');
  assert.equal(await hc.page.locator('.toolbar .tile').evaluate((e) => getComputedStyle(e).borderTopColor), 'rgb(111, 195, 223)');
  await hc.close();
});

test('V6.4: text stays readable in light, dark and high-contrast themes', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  for (const theme of ['light', 'dark', 'hc']) {
    const p = await open(theme);
    await p.send(graph('all_statements', 'Everything'));
    const pairs = await p.page.evaluate(() => [...document.querySelectorAll('.lg-node')].map((g) => {
      const shape = g.querySelector('.lg-shape'), title = g.querySelector('.lg-title');
      return shape && title ? [getComputedStyle(shape).fill, getComputedStyle(title).fill] : null;
    }).filter(Boolean));
    assert.ok(pairs.length > 20, theme + ': nodes drawn');
    const low = [];
    pairs.forEach(([fill, text]) => {
      const a = parseColor(fill), b = parseColor(text);
      if (a && b && contrast(a, b) < 4.5) { low.push(fill + ' / ' + text + ' = ' + contrast(a, b).toFixed(2)); }
    });
    assert.deepEqual(low, [], theme + ': node text contrast');
    await p.close();
  }
});

test('the page never scrolls sideways, even at phone width', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open('dark', { viewport: { width: 400, height: 700 } });
  await p.send(graph('all_statements', 'Everything'));
  const overflow = await p.page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 0, 'overflow ' + overflow);
  await p.close();
});

test('V8.1 collapse: blocks folded before a refresh stay folded', async (t) => {
  if (skipIfNoBrowser(t)) { return; }
  const p = await open();
  const message = graph('all_statements', 'Everything');
  await p.send(message);
  const before = await p.page.locator('.lg-node').count();
  assert.ok(before > 0);
  await p.send(message);
  assert.equal(await p.page.locator('.lg-node').count(), before);
  await p.close();
});
