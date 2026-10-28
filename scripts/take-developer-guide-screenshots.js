// Retakes the pictures in docs/images/developer-guide/ for DEVELOPER_GUIDE.md from real output:
//  - the terminal pictures are real `weave` commands run now, drawn into a terminal window (the generate picture replays a real
//    recorded generation: src/test/resources/generate/transcripts/d14.jsonl, made with a live model, so it needs no key);
//  - the graph and review pictures are the real panels of the VS Code extension fed with `weave graph` and `weave review` output
//    for the project that generation made.
// Needs the Loom module built (mvn -o -q compile dependency:copy-dependencies -DoutputDirectory=target/lib in
// src/loom/ai-agent4j-loom), the extension compiled (npx tsc -p tsconfig.test.json in src/loom/vscode-loom) and playwright with
// Chromium. Run from the repository root:  node scripts/take-developer-guide-screenshots.js
const path = require('path'), fs = require('fs'), os = require('os');
const { execFileSync } = require('child_process');
const pw = require('playwright');

const repo = path.join(__dirname, '..');
const module_ = path.join(repo, 'src', 'loom', 'ai-agent4j-loom');
const ext = path.join(repo, 'src', 'loom', 'vscode-loom');
const out = path.join(repo, 'docs', 'images', 'developer-guide');
const cp = process.env.WEAVE_CP || `${module_}/target/classes:${module_}/target/lib/*`;
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-dg-'));
fs.mkdirSync(out, { recursive: true });

const DESCRIPTION = fs.readFileSync(path.join(module_, 'src/test/resources/generate/descriptions/d14.txt'), 'utf8').trim();
const RECORDING = path.join(module_, 'src/test/resources/generate/transcripts/d14.jsonl');

function weave(args, cwd) {
  try {
    const text = execFileSync('java', ['-Dstdout.encoding=UTF-8', '-Dfile.encoding=UTF-8', '-cp', cp, 'io.github.llm4j.loom.cli.WeaveCLI', ...args],
      { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 << 20 });
    return text;
  } catch (e) { return (e.stdout || '') + (e.stderr || ''); }
}
const clean = (t) => t.split('\n').filter((l) => !/^Picked up JAVA_TOOL_OPTIONS/.test(l)).join('\n').split(work).join('.').replace(/\s+$/, '');

// ---- a terminal window ------------------------------------------------------------------------------------------------------
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
function paint(line) {
  const e = esc(line);
  if (/^(✓|ok )/.test(line)) return `<span class="g">${e.slice(0, 2)}</span>${e.slice(2)}`;
  if (/^(✗|fail )/.test(line)) return `<span class="r">${e}</span>`;
  if (/^! /.test(line)) return `<span class="y">${e}</span>`;
  if (/^[A-Z][A-Za-z ()]+$/.test(line) && line.length < 40 || /^[A-Z ]{4,}$/.test(line)) return `<b>${e}</b>`;
  return e
    .replace(/(\d+ passed, 0 failed)/, '<span class="g">$1</span>')
    .replace(/(nodes covered \d+\/\d+)/, '<span class="g">$1</span>')
    .replace(/(\$0\b)/, '<span class="g">$1</span>')
    .replace(/^(\s+)(✓)/, '$1<span class="g">$2</span>');
}
function terminalHtml(title, command, output, columns) {
  const body = output.split('\n').map((l) => `<div class="l">${paint(l) || '&nbsp;'}</div>`).join('');
  return `<!doctype html><meta charset="utf-8"><style>
  body{margin:0;background:transparent;font-family:'DejaVu Sans Mono',Menlo,Consolas,monospace}
  .win{width:${columns * 8.2 + 40}px;background:#0d1117;border-radius:12px;overflow:hidden;border:1px solid #30363d;box-shadow:0 18px 50px rgba(0,0,0,.45)}
  .bar{height:34px;background:#161b22;display:flex;align-items:center;padding:0 14px;gap:8px;border-bottom:1px solid #30363d}
  .dot{width:12px;height:12px;border-radius:50%}.t{margin-left:12px;color:#8b949e;font-size:12px;font-family:Inter,system-ui,sans-serif}
  .c{padding:16px 20px 20px;color:#c9d1d9;font-size:13.5px;line-height:1.5}
  .l{white-space:pre;overflow:hidden;text-overflow:ellipsis}.cmdl{white-space:pre-wrap;word-break:break-word}.p{color:#7ee787}.cmd{color:#fff;font-weight:600}
  .g{color:#3fb950}.r{color:#ff7b72}.y{color:#d29922}b{color:#fff}
  </style><div class="win"><div class="bar"><i class="dot" style="background:#ff5f56"></i><i class="dot" style="background:#ffbd2e"></i><i class="dot" style="background:#27c93f"></i><span class="t">${esc(title)}</span></div>
  <div class="c"><div class="cmdl"><span class="p">$</span> <span class="cmd">${esc(command)}</span></div><div class="l">&nbsp;</div>${body}</div></div>`;
}

(async () => {
  const browser = await pw.chromium.launch();
  async function terminal(name, title, command, output, columns = 118, maxLines = 60) {
    const lines = output.split('\n').slice(0, maxLines);
    const page = await (await browser.newContext({ viewport: { width: Math.round(columns * 8.2 + 80), height: 400 }, deviceScaleFactor: 2 })).newPage();
    await page.setContent(terminalHtml(title, command, lines.join('\n'), columns));
    await (await page.$('.win')).screenshot({ path: path.join(out, name + '.png'), omitBackground: true });
    await page.close();
    console.log('wrote', name);
  }

  // 1. try: no key, no network, nothing written
  const tried = clean(weave(['try', '--fast', '--no-color'], work));
  await terminal('terminal-try', 'weave try', 'weave try', tried.split('\n').filter((l) => !/^Demo models:/.test(l) || false).join('\n'), 140, 30);

  // 2. generate: a description in, a project out (replays the recorded live generation)
  const made = clean(weave(['generate', DESCRIPTION, '--replay', RECORDING, '--replay-by-order', '--no-ask', '--flat', '--out', 'meeting'], work));
  const upToReview = made.split('\n');
  const stop = upToReview.findIndex((l) => /^Review \(advice/.test(l));
  const shown = upToReview.slice(0, stop < 0 ? 40 : stop).filter((l) => !/^Generation cost/.test(l)).join('\n');
  const gens = upToReview.find((l) => /^Generation cost/.test(l)) || '';
  await terminal('terminal-generate', 'weave generate', `weave generate "${DESCRIPTION}" \\\n    --model gpt-6-luna`, shown + (gens ? '\n\n' + gens.replace(/ \(price of the model unknown\)/, '') : ''), 140, 42);

  const project = path.join(work, 'meeting');
  const script = path.join(project, 'main.loom');

  // 3. test: scripted models, no keys, every step reached
  const tests = clean(weave(['test', 'main.loom', '--coverage'], project));
  await terminal('terminal-test', 'weave test', 'weave test main.loom --coverage', tests, 100, 40);

  // 4. the command reference, like a man page
  const man = clean(weave(['man', 'generate'], work));
  await terminal('terminal-man', 'weave man', 'weave man generate', man, 100, 34);

  // 5. deploy: text files from the script
  const deploy = clean(weave(['deploy', 'main.loom', '--target', 'docker'], project));
  const dl = deploy.split('\n');
  const from = dl.findIndex((l) => /^ℹ|^✓ main.loom/.test(l));
  await terminal('terminal-deploy', 'weave deploy', 'weave deploy main.loom --target docker', dl.slice(from < 0 ? 0 : from).join('\n'), 118, 14);

  // 6. the graph and the review of the generated project, in the real panels of the extension
  const graphJson = JSON.parse(weave(['graph', script], project).replace(/^Picked up.*\n/m, ''));
  const reviewJson = JSON.parse(weave(['review', script, '--format', 'json', '--no-env'], project).replace(/^Picked up.*\n/m, ''));
  const THEMES = {
    light: ['vscode-light', '--vscode-editor-background:#ffffff;--vscode-foreground:#3b3b3b;--vscode-descriptionForeground:#717171;--vscode-panel-border:#e5e5e5;--vscode-focusBorder:#5980a6;--vscode-sideBar-background:#f8f8f8;--vscode-font-family:Inter,"Segoe UI",system-ui,sans-serif'],
    dark: ['vscode-dark', '--vscode-editor-background:#1f1f1f;--vscode-foreground:#cccccc;--vscode-descriptionForeground:#9d9d9d;--vscode-panel-border:#2b2b2b;--vscode-focusBorder:#0078d4;--vscode-sideBar-background:#181818;--vscode-font-family:Inter,"Segoe UI",system-ui,sans-serif'],
  };
  const { renderPanelHtml } = require(ext + '/out-test/src/graph/html.js');
  const { renderReviewHtml } = require(ext + '/out-test/src/review/html.js');
  const graphPage = path.join(work, 'graph.html'), reviewPage = path.join(work, 'review.html');
  const media = (n) => 'file://' + path.join(ext, 'media', n);
  fs.writeFileSync(graphPage, renderPanelHtml({ cspSource: 'file:', nonce: 'n', uri: media }));
  fs.writeFileSync(reviewPage, renderReviewHtml({ cspSource: 'file:', nonce: 'n', uri: media }));
  const wf = graphJson.workflows.find((w) => w.name === 'Main') || graphJson.workflows[0];
  const ask = wf.nodes.find((n) => n.kind === 'human_prompt') || wf.nodes.find((n) => n.kind === 'delegate');

  async function shot(page, name, h) {
    await page.screenshot({ path: path.join(out, name + '.png'), clip: { x: 0, y: 0, width: 1280, height: h } });
    console.log('wrote', name);
  }
  for (const theme of ['dark', 'light']) {
    const page = await (await browser.newContext({ viewport: { width: 1280, height: 760 }, deviceScaleFactor: 1.5 })).newPage();
    await page.addInitScript(() => { window.acquireVsCodeApi = () => ({ postMessage() {}, getState: () => null, setState() {} }); });
    await page.goto('file://' + graphPage);
    await page.evaluate(([c, v]) => { document.body.className = c; document.documentElement.style.cssText = v; }, THEMES[theme]);
    await page.evaluate((m) => window.postMessage(m, '*'), { type: 'graph', workflow: wf.name, result: graphJson, sections: {} });
    await page.waitForTimeout(150);
    await page.click('.vbtn[data-view=flow]');
    await page.evaluate((id) => { const el = document.querySelector('#rail .seg[data-id="' + id + '"]'); if (el) el.click(); }, ask.id);
    await page.waitForTimeout(250);
    await shot(page, 'graph-meeting-' + theme, 760);
    await page.close();
  }
  {
    const page = await (await browser.newContext({ viewport: { width: 1280, height: 760 }, deviceScaleFactor: 1.5 })).newPage();
    await page.addInitScript(() => { window.acquireVsCodeApi = () => ({ postMessage() {}, getState: () => null, setState() {} }); });
    await page.goto('file://' + reviewPage);
    await page.evaluate(([c, v]) => { document.body.className = c; document.documentElement.style.cssText = v; }, THEMES.dark);
    await page.evaluate((m) => window.postMessage(m, '*'), { type: 'review', review: reviewJson, graph: graphJson, status: 'idle', entry: script });
    await page.waitForTimeout(150);
    await page.click('[data-layout="split"]');
    await page.waitForTimeout(100);
    const first = page.locator('#main [data-finding]').first();
    if (await first.count()) await first.click();
    await page.waitForTimeout(150);
    await shot(page, 'review-meeting-dark', 760);
    await page.close();
  }

  // 7. the commands the extension adds, listed as the Command Palette lists them (drawn from the extension's manifest, not a screenshot of VS Code)
  {
    const manifest = JSON.parse(fs.readFileSync(path.join(ext, 'package.json'), 'utf8'));
    const hidden = new Set(['loom.restartEngine', 'loom.showEngineLog', 'loom.javaHelp', 'loom.review.accept', 'loom.review.dismiss', 'loom.review.fixFinding', 'loom.review.explain', 'loom.review.restore', 'loom.review.baseline', 'loom.review.fixAll', 'loom.createPromptFile', 'loom.runWorkflow']);
    const order = ['loom.try', 'loom.doctor', 'loom.openExample', 'loom.generate', 'loom.change', 'loom.showGraph', 'loom.review', 'loom.test', 'loom.eval', 'loom.debug', 'loom.observe', 'loom.deploy', 'loom.next', 'loom.commands', 'loom.openGuide', 'loom.installSkill'];
    const titles = Object.fromEntries(manifest.contributes.commands.map((c) => [c.command, c.title]));
    const icons = { 'loom.try': '👋', 'loom.doctor': '🩺', 'loom.openExample': '📂', 'loom.generate': '✍️', 'loom.change': '🔧', 'loom.showGraph': '👀', 'loom.review': '🛡️', 'loom.test': '🧪', 'loom.eval': '📊', 'loom.debug': '🐞', 'loom.observe': '🔭', 'loom.deploy': '🚢', 'loom.next': '🧭', 'loom.commands': '📖', 'loom.openGuide': '📚', 'loom.installSkill': '🤝' };
    const rows = order.filter((id) => titles[id] && !hidden.has(id)).map((id, i) => `<div class="r${i === 0 ? ' on' : ''}"><span class="i">${icons[id] || ''}</span>${esc(titles[id].replace(/^Loom: /, ''))}<span class="k">Loom</span></div>`).join('');
    const page = await (await browser.newContext({ viewport: { width: 760, height: 200 }, deviceScaleFactor: 2 })).newPage();
    await page.setContent(`<!doctype html><meta charset="utf-8"><style>
      body{margin:0;background:transparent;font-family:Inter,'Segoe UI',system-ui,sans-serif}
      .p{width:680px;background:#252526;border:1px solid #454545;border-radius:8px;box-shadow:0 18px 50px rgba(0,0,0,.5);overflow:hidden;color:#ccc}
      .q{margin:8px;padding:6px 10px;background:#3c3c3c;border:1px solid #007fd4;border-radius:3px;font-size:14px;color:#fff}
      .r{display:flex;align-items:center;padding:5px 16px;font-size:14px;height:22px}.r.on{background:#04395e;color:#fff}
      .i{width:30px;font-size:15px}.k{margin-left:auto;color:#8b8b8b;font-size:12px}
    </style><div class="p"><div class="q">&gt; Loom</div>${rows}<div style="height:8px"></div></div>`);
    await (await page.$('.p')).screenshot({ path: path.join(out, 'vscode-commands.png'), omitBackground: true });
    await page.close();
    console.log('wrote vscode-commands');
  }

  await browser.close();
  fs.rmSync(work, { recursive: true, force: true });
})();
