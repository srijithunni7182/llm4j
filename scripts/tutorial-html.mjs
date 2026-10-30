// Makes the tutorial's web page from the markdown in docs/tutorial (see scripts/make-tutorial-html.sh, which installs the one dependency).
// The diagrams are drawn to SVG here, with Mermaid in a headless browser, so the page needs no script and no connection.
// Needs a Chromium: set CHROMIUM_PATH to one, or install Playwright's (npx playwright install chromium), or have Chrome.
// Usage: node tutorial-html.mjs <repository root> <output html>
import fs from 'fs';
import crypto from 'crypto';
import { createRequire } from 'module';
import { chromium } from 'playwright-core';
import path from 'path';
import { marked } from 'marked';

const REPO = process.argv[2];
if (!REPO || !process.argv[3]) throw new Error('usage: node tutorial-html.mjs <repository root> <output html>');
const DIR = path.join(REPO, 'docs/tutorial');
const OUT = process.argv[3];
const GH = 'https://github.com/srijithunni7182/llm4j';

const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const slug = (t) =>
  [...t.toLowerCase()].map((c) => (/[\p{L}\p{N}_-]/u.test(c) ? c : c === ' ' ? '-' : '')).join('');

const files = fs.readdirSync(DIR).filter((f) => f.endsWith('.md')).sort();
// README first, then 01..14
const order = ['README.md', ...files.filter((f) => /^\d\d-/.test(f))];
const sourceHash = (() => {
  const h = crypto.createHash('sha256');
  for (const f of order) h.update(f + '\n' + fs.readFileSync(path.join(DIR, f), 'utf8') + '\n');
  return h.digest('hex');
})();
const chapterId = (f) => (f === 'README.md' ? 'c00' : 'c' + f.slice(0, 2));

const KEYWORDS = new Set(
  ('agent workflow delegate to return alt else loop until max on_exhausted on_failure on_stopped for each in parallel broadcast handoff call import ' +
    'run intentclassify decide human_prompt sleep set checkpoint rewind note observe halt guardrail on_violation supervise decision schedule persona provider routing ' +
    'knowledge tool mcp intentclassifier budget rate_limits substitution shared_prompt audit retry backoff timeout expecting starting with at most times carrying ' +
    'when it still fails side effects ladder rung').split(/\s+/)
);

function highlightLoom(code) {
  // tokens: comments, strings, numbers, keywords
  const re = /(\/\/[^\n]*)|("(?:\\.|[^"\\])*")|(\b\d+(?:\.\d+)?\w*\b)|(\b[A-Za-z_][A-Za-z_0-9]*\b)/g;
  let out = '';
  let last = 0;
  for (const m of code.matchAll(re)) {
    out += esc(code.slice(last, m.index));
    last = m.index + m[0].length;
    if (m[1]) out += `<span class="tk-c">${esc(m[1])}</span>`;
    else if (m[2]) out += `<span class="tk-s">${esc(m[2]).replace(/\{([^{}\s]+)\}/g, '<span class="tk-v">{$1}</span>')}</span>`;
    else if (m[3]) out += `<span class="tk-n">${esc(m[3])}</span>`;
    else if (KEYWORDS.has(m[4])) out += `<span class="tk-k">${esc(m[4])}</span>`;
    else out += esc(m[4]);
  }
  return out + esc(code.slice(last));
}

function resolveHref(href, file) {
  if (/^(https?:|mailto:)/.test(href)) return href;
  const [p, anchor] = href.split('#');
  if (!p) return '#' + chapterId(file) + (anchor ? '-' + anchor : '');
  const target = path.resolve(DIR, p);
  const name = path.basename(target);
  if (path.dirname(target) === DIR && order.includes(name)) return '#' + chapterId(name) + (anchor ? '-' + anchor : '');
  const rel = path.relative(REPO, target);
  const isDir = fs.existsSync(target) && fs.statSync(target).isDirectory();
  return `${GH}/${isDir ? 'tree' : 'blob'}/main/${rel}${anchor ? '#' + anchor : ''}`;
}

const diagramSources = [];
const chapters = [];
for (const f of order) {
  const id = chapterId(f);
  const src = fs.readFileSync(path.join(DIR, f), 'utf8');
  const seen = new Map();
  const renderer = new marked.Renderer();
  renderer.heading = (text, level, raw) => {
    let s = slug(raw.replace(/`/g, '').replace(/\[([^\]]*)\]\([^)]*\)/g, '$1'));
    const n = seen.get(s) || 0;
    seen.set(s, n + 1);
    const hid = id + '-' + s + (n ? '-' + n : '');
    return `<h${level} id="${hid}">${text}</h${level}>\n`;
  };
  renderer.code = (code, info) => {
    const lang = (info || '').split(/\s+/)[0];
    if (lang === 'mermaid') {
      diagramSources.push(code);
      return `<!--DIAGRAM:${diagramSources.length - 1}-->\n`;
    }
    if (lang.startsWith('loom')) {
      const tag = lang === 'loom-error' ? 'this one is meant to fail' : lang === 'loom-parse' ? 'excerpt' : 'Loom';
      return `<figure class="code"><figcaption>${tag}</figcaption><pre><code>${highlightLoom(code)}</code></pre></figure>\n`;
    }
    const label = lang || 'text';
    return `<figure class="code"><figcaption>${esc(label)}</figcaption><pre><code>${esc(code)}</code></pre></figure>\n`;
  };
  renderer.link = (href, title, text) => {
    const h = resolveHref(href, f);
    const ext = /^https?:/.test(h);
    return `<a href="${h}"${ext ? ' target="_blank" rel="noopener"' : ''}>${text}</a>`;
  };
  renderer.html = (html) => (html.trim().startsWith('<!--') ? '' : html);
  const body = marked.parse(src, { renderer, gfm: true });
  const titleMatch = src.match(/^#\s+(.+)$/m);
  chapters.push({ id, file: f, title: titleMatch ? titleMatch[1].replace(/`/g, '') : f, body });
}


const requireHere = createRequire(import.meta.url);

/** One Mermaid configuration for every diagram: the page's own colours, a safe font, and the natural size of each drawing. */
const MERMAID_CONFIG = {
  startOnLoad: false,
  securityLevel: 'strict',
  theme: 'base',
  themeVariables: {
    fontFamily: 'Arial, Helvetica, "Liberation Sans", sans-serif',
    fontSize: '15px',
    primaryColor: '#d9efec',
    primaryBorderColor: '#0f766e',
    primaryTextColor: '#161a2b',
    secondaryColor: '#eef0f7',
    tertiaryColor: '#f5f6fa',
    lineColor: '#5a6078',
    textColor: '#161a2b',
    noteBkgColor: '#fff6d6',
    noteTextColor: '#161a2b',
    actorBkg: '#d9efec',
    actorBorder: '#0f766e',
    signalColor: '#5a6078',
    clusterBkg: '#f5f6fa',
    clusterBorder: '#b9c0d4',
  },
  flowchart: { useMaxWidth: false, htmlLabels: false, nodeSpacing: 26, rankSpacing: 26, padding: 8, curve: 'basis' },
  sequence: { useMaxWidth: false, actorMargin: 38, boxMargin: 8, messageMargin: 28, width: 130 },
  state: { useMaxWidth: false },
};

async function drawDiagrams(sources) {
  if (sources.length === 0) return [];
  const launch = process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {};
  let browser;
  try {
    browser = await chromium.launch(launch);
  } catch (first) {
    try {
      browser = await chromium.launch({ channel: 'chrome' });
    } catch {
      throw new Error('No Chromium to draw the diagrams with. Set CHROMIUM_PATH, or run: npx playwright install chromium. (' + first.message.split('\n')[0] + ')');
    }
  }
  try {
    const page = await browser.newPage();
    await page.setContent('<!doctype html><html><body></body></html>');
    await page.addScriptTag({ path: requireHere.resolve('mermaid/dist/mermaid.min.js') });
    await page.evaluate((config) => window.mermaid.initialize(config), MERMAID_CONFIG);
    const out = [];
    for (let i = 0; i < sources.length; i++) {
      out.push(await page.evaluate(async ([id, code]) => (await window.mermaid.render(id, code)).svg, ['d' + i, sources[i]]));
    }
    return out;
  } finally {
    await browser.close();
  }
}

/** The drawing as an inline SVG at its natural size (never wider than the column), named for a reader who cannot see it. */
function figure(svg, n) {
  const fixed = svg.replace(/<svg /, `<svg role="img" aria-label="Diagram ${n}" `).replace(/ style="max-width:[^"]*"/, '');
  return `<figure class="diagram"><div class="plate">${fixed}</div><button type="button" class="bigger" aria-label="Show the diagram larger">Larger</button></figure>\n`;
}

const drawings = await drawDiagrams(diagramSources);
for (const c of chapters) c.body = c.body.replace(/<!--DIAGRAM:(\d+)-->/g, (_, i) => figure(drawings[+i], +i + 1));

const nav = chapters
  .map((c) => {
    const m = c.title.match(/^(\d+)\.\s*(.*)$/);
    return c.id === 'c00'
      ? `<li><a href="#c00"><span class="n">·</span>Start here</a></li>`
      : `<li><a href="#${c.id}"><span class="n">${m ? m[1] : ''}</span>${m ? m[2] : c.title}</a></li>`;
  })
  .join('\n');

const sections = chapters
  .map((c) => `<section class="chapter" id="${c.id}">\n${c.body}\n</section>`)
  .join('\n');

const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<!-- source-sha256: ${sourceHash} -->
<title>The Loom Tutorial</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,500;12..96,700&family=Newsreader:ital,opsz,wght@0,6..72,400;0,6..72,500;1,6..72,400&family=JetBrains+Mono:wght@400;500&display=swap">
<style>
/* Layout concept: a reading room. A slim chapter rail on the left, one long column of prose, wide diagrams that sit on their own plates. */
:root {
  --bg: #f5f6fa;
  --paper: #ffffff;
  --ink: #161a2b;
  --muted: #5a6078;
  --rule: #dfe2ec;
  --thread: #0f766e;          /* the one accent: a teal warp thread */
  --thread-soft: #d9efec;
  --code-bg: #101426;
  --code-ink: #dfe4f7;
  --tk-k: #8bb8ff;
  --tk-s: #9be0b2;
  --tk-c: #7d86a6;
  --tk-n: #f2c374;
  --tk-v: #f19ac7;
  --display: "Bricolage Grotesque", "Segoe UI", system-ui, sans-serif;
  --body: "Newsreader", Georgia, "Times New Roman", serif;
  --mono: "JetBrains Mono", ui-monospace, "SFMono-Regular", Menlo, Consolas, monospace;
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --bg: #0d101c; --paper: #141828; --ink: #e6e9f6; --muted: #98a0bd; --rule: #262c44;
    --thread: #3cc4b6; --thread-soft: #14363a; --code-bg: #0a0d18;
    color-scheme: dark;
  }
}
:root[data-theme="dark"] {
  --bg: #0d101c; --paper: #141828; --ink: #e6e9f6; --muted: #98a0bd; --rule: #262c44;
  --thread: #3cc4b6; --thread-soft: #14363a; --code-bg: #0a0d18;
  color-scheme: dark;
}
* { box-sizing: border-box; }
html { scroll-behavior: smooth; scroll-padding-top: 76px; }
body {
  margin: 0; background: var(--bg); color: var(--ink);
  font-family: var(--body); font-size: 18px; line-height: 1.62;
  padding-left: max(16px, env(safe-area-inset-left, 0px));
  padding-right: max(16px, env(safe-area-inset-right, 0px));
  padding-block: 0 64px;
}
body::before { content: ""; position: fixed; inset: 0 0 auto; height: env(safe-area-inset-top, 0px); z-index: 30; background: var(--bg); }
.top {
  position: sticky; top: env(safe-area-inset-top, 0px); z-index: 20; background: var(--bg);
  border-bottom: 1px solid var(--rule); margin: 0 calc(-1 * max(16px, env(safe-area-inset-left, 0px)));
  padding: 10px max(16px, env(safe-area-inset-right, 0px)) 10px max(16px, env(safe-area-inset-left, 0px));
  display: flex; align-items: center; gap: 14px; flex-wrap: wrap;
}
.top .brand { font-family: var(--display); font-weight: 700; font-size: 17px; letter-spacing: -0.01em; }
.top .brand i { font-style: normal; color: var(--thread); }
.top select {
  font: inherit; font-family: var(--display); font-size: 14px; color: var(--ink); background: var(--paper);
  border: 1px solid var(--rule); border-radius: 6px; padding: 6px 8px; max-width: 100%; min-width: 0;
}
.top .where { color: var(--muted); font-family: var(--display); font-size: 13px; margin-left: auto; }
.shell { max-width: 1120px; margin: 0 auto; display: grid; grid-template-columns: 230px minmax(0, 1fr); gap: 48px; padding-top: 28px; }
nav.rail { position: sticky; top: 76px; align-self: start; max-height: calc(100vh - 96px); overflow-y: auto; font-family: var(--display); font-size: 14px; }
nav.rail ol { list-style: none; margin: 0; padding: 0; display: grid; gap: 2px; }
nav.rail a { display: flex; gap: 10px; padding: 6px 10px; border-radius: 6px; color: var(--muted); text-decoration: none; line-height: 1.3; }
nav.rail a:hover { color: var(--ink); background: var(--thread-soft); }
nav.rail a.on { color: var(--ink); background: var(--thread-soft); font-weight: 700; }
nav.rail .n { width: 1.6em; flex: none; text-align: right; color: var(--thread); font-variant-numeric: tabular-nums; font-weight: 700; }
main { min-width: 0; }
.chapter { background: var(--paper); border: 1px solid var(--rule); border-radius: 10px; padding: 40px clamp(18px, 4vw, 52px) 44px; margin-bottom: 28px; min-width: 0; }
h1, h2, h3 { font-family: var(--display); line-height: 1.15; letter-spacing: -0.015em; text-wrap: balance; }
h1 { font-size: clamp(30px, 4.4vw, 42px); margin: 0 0 22px; }
h2 { font-size: 26px; margin: 2.1em 0 0.5em; padding-top: 0.6em; border-top: 1px solid var(--rule); }
h3 { font-size: 20px; margin: 1.7em 0 0.4em; }
h4 { font-family: var(--display); font-size: 17px; margin: 1.4em 0 0.3em; }
p, li { max-width: 68ch; }
a { color: var(--thread); text-underline-offset: 3px; }
code { overflow-wrap: anywhere; font-family: var(--mono); font-size: 0.82em; background: var(--thread-soft); padding: 0.12em 0.38em; border-radius: 4px; }
figure.code { margin: 1.3em 0; border-radius: 8px; background: var(--code-bg); overflow: hidden; min-width: 0; }
figure.code figcaption { font-family: var(--display); font-size: 11.5px; letter-spacing: 0.08em; text-transform: uppercase; color: #8c95b8; padding: 8px 14px 0; }
figure.code pre { margin: 0; padding: 8px 14px 14px; overflow-x: auto; }
figure.code code { overflow-wrap: normal; background: none; padding: 0; color: var(--code-ink); font-size: 13.5px; line-height: 1.55; white-space: pre; }
.tk-k { color: var(--tk-k); } .tk-s { color: var(--tk-s); } .tk-c { color: var(--tk-c); font-style: italic; } .tk-n { color: var(--tk-n); } .tk-v { color: var(--tk-v); }
/* A diagram is drawn once, in light colours, so it sits on a light plate in either theme. */
.diagram { position: relative; margin: 1.5em 0; padding: 14px; background: #f5f6fa; border: 1px solid #dfe2ec; border-radius: 8px; }
.diagram .plate { overflow: auto; text-align: center; max-height: 540px; }
.diagram svg { display: inline-block; max-width: 100%; height: auto; max-height: 520px; vertical-align: top; }
.diagram.open .plate { max-height: none; }
.diagram.open svg { max-height: none; }
.diagram .bigger { position: absolute; top: 8px; right: 8px; font: 600 12px var(--display); color: #0f766e; background: #ffffff; border: 1px solid #b9c0d4; border-radius: 6px; padding: 3px 9px; cursor: pointer; }
.diagram .bigger:hover { background: #d9efec; }
.tablewrap, table { max-width: 100%; }
table { border-collapse: collapse; margin: 1.3em 0; display: block; overflow-x: auto; font-size: 15.5px; line-height: 1.45; }
th { font-family: var(--display); font-size: 13px; text-align: left; letter-spacing: 0.02em; color: var(--muted); }
th, td { padding: 8px 12px; border-bottom: 1px solid var(--rule); vertical-align: top; }
td code, th code { white-space: nowrap; }
blockquote { margin: 1.3em 0; padding: 4px 18px; border-left: 3px solid var(--thread); color: var(--muted); background: var(--thread-soft); border-radius: 0 6px 6px 0; max-width: 68ch; }
blockquote p { margin: 0.5em 0; }
ul, ol { padding-left: 1.3em; }
hr { border: 0; border-top: 1px solid var(--rule); margin: 2em 0; }
.foot { text-align: center; color: var(--muted); font-family: var(--display); font-size: 14px; margin-top: 8px; }
@media (max-width: 860px) {
  .shell { grid-template-columns: minmax(0, 1fr); gap: 0; }
  nav.rail { display: none; }
  body { font-size: 17px; }
}
@media (min-width: 861px) { .top select { display: none; } }
@media (prefers-reduced-motion: reduce) { html { scroll-behavior: auto; } }
a:focus-visible, select:focus-visible { outline: 2px solid var(--thread); outline-offset: 2px; }
</style>
</head>
<body>
<header class="top">
  <span class="brand">Loom<i> tutorial</i></span>
  <select id="jump" aria-label="Jump to a chapter">
${chapters.map((c) => `    <option value="${c.id}">${c.id === 'c00' ? 'Start here' : c.title}</option>`).join('\n')}
  </select>
  <span class="where" id="where">Start here</span>
</header>
<div class="shell">
  <nav class="rail" aria-label="Chapters"><ol>
${nav}
  </ol></nav>
  <main>
${sections}
    <p class="foot">This tutorial and its fifteen starter workflows live in the <a href="${GH}" target="_blank" rel="noopener">llm4j repository</a>.</p>
  </main>
</div>
<script>
(function () {
  var links = Array.prototype.slice.call(document.querySelectorAll('nav.rail a'));
  var where = document.getElementById('where');
  var jump = document.getElementById('jump');
  var chapters = Array.prototype.slice.call(document.querySelectorAll('section.chapter'));
  function current() {
    var y = window.scrollY + 120, id = chapters[0].id;
    chapters.forEach(function (c) { if (c.offsetTop <= y) id = c.id; });
    return id;
  }
  function mark() {
    var id = current();
    links.forEach(function (a) { a.classList.toggle('on', a.getAttribute('href') === '#' + id); });
    var a = links.filter(function (l) { return l.getAttribute('href') === '#' + id; })[0];
    where.textContent = a ? a.textContent.replace(/^[·\\d]+/, '').trim() : '';
    jump.value = id;
  }
  document.addEventListener('click', function (e) {
    var b = e.target.closest && e.target.closest('.bigger');
    if (!b) return;
    var d = b.parentNode;
    var open = d.classList.toggle('open');
    b.textContent = open ? 'Smaller' : 'Larger';
  });
  jump.addEventListener('change', function () { location.hash = jump.value; });
  window.addEventListener('scroll', mark, { passive: true });
  mark();
})();
</script>
</body>
</html>
`;

fs.writeFileSync(OUT, html);
console.log('wrote', OUT, (html.length / 1024).toFixed(0) + ' KB', chapters.length, 'sections');
