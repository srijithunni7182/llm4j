// Parses every .mmd file in a directory with the real Mermaid parser. Usage: node mermaid-parse.mjs <dir> (needs mermaid and jsdom)
import { JSDOM } from 'jsdom';
import fs from 'fs';
const dom = new JSDOM('<!doctype html><body></body>');
globalThis.window = dom.window; globalThis.document = dom.window.document;
Object.defineProperty(globalThis, 'navigator', { value: dom.window.navigator, configurable: true });
const { default: mermaid } = await import('mermaid');
mermaid.initialize({ startOnLoad: false });
const dir = process.argv[2];
let failed = 0, count = 0;
for (const f of fs.readdirSync(dir).filter((n) => n.endsWith('.mmd')).sort()) {
  // a file holds one flowchart per workflow, each after a "%% workflow" comment
  const parts = fs.readFileSync(dir + '/' + f, 'utf8').split(/^%% workflow .*$/m).map((s) => s.trim()).filter(Boolean);
  for (const [i, text] of parts.entries()) {
    count++;
    try { if (!text.startsWith('flowchart TD')) { throw new Error('not a flowchart'); } const r = await mermaid.parse(text); if (!r) { throw new Error('parse returned ' + r); } }
    catch (e) { failed++; console.log('FAIL', f, '#' + (i + 1), String(e.message || e).split('\n')[0]); }
  }
}
console.log(`${count - failed}/${count} flowcharts parse`);
process.exit(failed ? 1 : 0);
