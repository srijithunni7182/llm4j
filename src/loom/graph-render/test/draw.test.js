'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const G = require('../graph-render.js');
const { golden, workflow, agents, big } = require('./fixtures.js');

const body = (svg) => svg.replace(/<style>[\s\S]*?<\/style>/, '');
const hostile = '<img src=x onerror=alert(1)> "quoted" \'single\' </script> & more';

test('VS.1: text from a script is escaped everywhere it is drawn', () => {
  const g = { nodes: [
    { id: 'start', kind: 'start', label: 'Start' },
    { id: 'n1', kind: 'delegate', label: 'delegate ' + hostile, agent: hostile, attrs: { text: hostile, variable: hostile, expecting: hostile } },
    { id: 'n2', kind: 'note', label: 'note', attrs: { text: hostile } },
    { id: 'end', kind: 'end', label: 'End' }],
    edges: [{ from: 'start', to: 'n1', label: hostile }, { from: 'n1', to: 'n2' }, { from: 'n2', to: 'end' }] };
  const svg = G.svgText(g, { agents: { [hostile]: { model: hostile, approve: [hostile] } } });
  assert.ok(!/<img/.test(svg), 'no injected tag');
  assert.ok(!/<\/script/.test(svg), 'no closing script tag');
  assert.ok(!/onerror=alert\(1\)>/.test(svg.replace(/&lt;img src=x onerror=alert\(1\)&gt;/g, '')), 'attribute text only appears escaped');
  assert.ok(svg.includes('&lt;img'), 'the text is still shown');
  assert.equal((svg.match(/<svg/g) || []).length, 1);
});

test('long text is cut, the tooltip is capped, and a node is never widened beyond its chips', () => {
  const long = 'x'.repeat(5000);
  const lay = G.layout({ nodes: [{ id: 'n1', kind: 'note', label: long, attrs: { text: long } }], edges: [] }, {});
  assert.ok(lay.nodes[0].w <= 400);
  assert.ok(G.title({ id: 'n', label: long }).length <= 60);
  assert.ok(!G.svgText({ nodes: [{ id: 'n1', kind: 'note', label: long, attrs: { text: long } }], edges: [] }).includes('x'.repeat(600)), 'the tooltip holds at most 500 characters');
});

test('each kind is drawn with the shape ui.md gives it', () => {
  const kinds = { alt: '<polygon', parallel: 'class="lg-bar"', call: 'class="lg-dbl"', checkpoint: '<path class="lg-shape"', start: 'rx="15"', loop: 'rx="16"' };
  Object.entries(kinds).forEach(([kind, marker]) => {
    const svg = G.svgText({ nodes: [{ id: 'n1', kind, label: kind }], edges: [] });
    assert.ok(svg.includes(marker), kind + ' should contain ' + marker);
  });
  ['note', 'unknown'].forEach((kind) => assert.ok(G.svgText({ nodes: [{ id: 'n1', kind, label: kind }], edges: [] }).includes('lg-dashed')));
});

test('an unresolved call is drawn dashed in the warning colour with a question chip', () => {
  const svg = G.svgText({ nodes: [{ id: 'n1', kind: 'call', label: 'call Nowhere', unresolved: true, call: { workflow: 'Nowhere' } }], edges: [] });
  assert.ok(svg.includes('lg-bad'));
  assert.ok(svg.includes('lg-dashed'));
  assert.ok(svg.includes('>?<'));
  assert.ok(svg.includes('not found'));
});

test('every node is a focusable button with a label that names its line', () => {
  const result = golden('content_factory');
  const svg = G.svgText(workflow(result, 'GenerateContent'), { agents: agents(result) });
  const nodes = svg.match(/<g class="lg-node[^>]*>/g);
  assert.equal(nodes.length, 8);
  nodes.forEach((g) => { assert.ok(g.includes('role="button"') && g.includes('tabindex="0"') && g.includes('aria-label="')); });
  assert.ok(svg.includes('delegate Researcher, line 5'));
});

test('a node whose agent needs approval shows the approval hand', () => {
  const svg = G.svgText({ nodes: [{ id: 'n1', kind: 'delegate', label: 'delegate A', agent: 'A' }], edges: [] }, { agents: { A: { approve: ['send_email'] } } });
  assert.ok(svg.includes('Needs approval: send_email'));
  assert.ok(!G.svgText({ nodes: [{ id: 'n1', kind: 'delegate', label: 'delegate A', agent: 'A' }], edges: [] }, { agents: { A: { approve: [] } } }).includes('Needs approval'));
});

test('the drawing carries its own styles, a marker and a viewBox, and loads nothing', () => {
  const svg = G.svgText({ nodes: [{ id: 'n1', kind: 'note', label: 'x' }], edges: [] });
  assert.ok(svg.includes('<style>') && svg.includes('<marker id="lg-arrow"') && /viewBox="[-\d. ]+"/.test(svg));
  assert.ok(!/https?:\/\//.test(svg.replace('http://www.w3.org/2000/svg', '')));
  assert.ok(!/<script|<image|url\(http|@import/.test(svg));
});

test('the module has no imports and makes no network calls', () => {
  const src = require('fs').readFileSync(require.resolve('../graph-render.js'), 'utf8');
  assert.ok(!/\brequire\(/.test(src));
  assert.ok(!/\bfetch\(|XMLHttpRequest|WebSocket|importScripts|eval\(|new Function/.test(src));
});

test('edge labels are drawn and a rewind edge is dashed', () => {
  const g = { nodes: [{ id: 'a', kind: 'checkpoint', label: 'checkpoint a' }, { id: 'b', kind: 'rewind', label: 'rewind to a' }], edges: [{ from: 'a', to: 'b' }, { from: 'b', to: 'a', label: 'rewind' }] };
  const svg = G.svgText(g);
  assert.ok(svg.includes('lg-dash'));
  assert.ok(svg.includes('>rewind<'));
});

test('overlay: nodes on both paths are ok, only expected is missed, only actual is unexpected, neither is none', () => {
  const g = { nodes: ['start', 'n1', 'n2', 'n3', 'end'].map((id) => ({ id, kind: id === 'start' || id === 'end' ? id : 'note', label: id })), edges: [{ from: 'start', to: 'n1' }, { from: 'n1', to: 'n2' }, { from: 'n2', to: 'end' }, { from: 'n1', to: 'n3' }, { from: 'n3', to: 'end' }] };
  const o = G.overlay(g, ['start', 'n1', 'n2', 'end'], ['start', 'n1', 'n3', 'end']);
  assert.deepEqual(o.states, { start: 'ok', n1: 'ok', n2: 'missed', n3: 'unexpected', end: 'ok' });
  assert.deepEqual(o.traversed, [['start', 'n1'], ['n1', 'n3'], ['n3', 'end']]);
  assert.equal(o.hasExpected, true);
});

test('overlay without an expected path shows only taken and not visited', () => {
  const g = { nodes: ['start', 'n1', 'n2', 'end'].map((id) => ({ id, kind: 'note', label: id })), edges: [] };
  const o = G.overlay(g, [], ['start', 'n1', 'end']);
  assert.deepEqual(o.states, { start: 'taken', n1: 'taken', n2: 'none', end: 'taken' });
  assert.equal(o.hasExpected, false);
  const svg = G.svgText(g, { overlay: o });
  assert.ok(!/lg-state-(missed|unexpected)/.test(body(svg)));
});

test('overlay counts visits, so a loop run three times shows ×3 and a node run once shows none', () => {
  const g = { nodes: ['start', 'l', 'b', 'end'].map((id) => ({ id, kind: 'note', label: id })), edges: [{ from: 'start', to: 'l' }, { from: 'l', to: 'b' }, { from: 'b', to: 'l', label: 'again' }, { from: 'l', to: 'end' }] };
  const o = G.overlay(g, [], ['start', 'l', 'b', 'l', 'b', 'l', 'b', 'l', 'end']);
  assert.equal(o.visits.l, 4);
  assert.equal(o.visits.b, 3);
  assert.equal(o.visits.start, 1);
  const svg = G.svgText(g, { overlay: o });
  assert.ok(svg.includes('×3') && svg.includes('×4'));
  assert.ok(!svg.includes('×1'));
});

test('overlay marks carry a glyph and words, never only colour', () => {
  const g = { nodes: ['start', 'a', 'b', 'c', 'end'].map((id) => ({ id, kind: id === 'start' || id === 'end' ? id : 'note', label: id })), edges: [{ from: 'start', to: 'a' }, { from: 'a', to: 'end' }, { from: 'start', to: 'b' }, { from: 'b', to: 'end' }] };
  const svg = G.svgText(g, { overlay: G.overlay(g, ['start', 'a', 'c', 'end'], ['start', 'a', 'b', 'end']) });
  assert.ok(svg.includes('✓'));
  assert.ok(svg.includes('✗ missed'));
  assert.ok(svg.includes('! unexpected'));
  assert.ok(!body(svg).includes('lg-dim'), 'nothing is not visited in this run');
  const idle = G.svgText(g, { overlay: G.overlay(g, [], []) });
  assert.ok(body(idle).includes('lg-dim'), 'not visited nodes are dimmed');
});

test('overlay: traversed edges are heavier and the rest are faint', () => {
  const g = { nodes: ['start', 'a', 'end'].map((id) => ({ id, kind: id === 'a' ? 'note' : id, label: id })), edges: [{ from: 'start', to: 'a' }, { from: 'a', to: 'end' }] };
  const svg = G.svgText(g, { overlay: G.overlay(g, [], ['start', 'a']) });
  assert.equal((body(svg).match(/lg-trav/g) || []).length, 1);
  assert.equal((body(svg).match(/lg-untrav/g) || []).length, 1);
});

test('large graphs collapse the biggest blocks first until at most 300 nodes show', () => {
  const g = big(11, 12);
  const collapsed = G.defaultCollapsed(g, 300);
  assert.ok(collapsed.size > 0);
  const lay = G.layout(g, {}, collapsed);
  assert.ok(lay.nodes.length <= 300, 'visible ' + lay.nodes.length);
  assert.ok(lay.nodes.filter((n) => n.hidden).every((n) => n.chips.some((c) => c.t.endsWith(' steps'))));
  assert.equal(G.defaultCollapsed({ nodes: g.nodes.slice(0, 10), edges: [] }, 300).size, 0);
});

test('expanding one block adds exactly its children', () => {
  const g = big(11, 12);
  const collapsed = G.defaultCollapsed(g, 300);
  const id = [...collapsed][0], counts = G.descendantCounts(g);
  const before = G.layout(g, {}, collapsed).nodes.length;
  const after = new Set(collapsed); after.delete(id);
  const expanded = G.layout(g, {}, after).nodes.length;
  assert.equal(expanded - before, counts[id], 'exactly the steps inside the block appear');
});

test('a collapsed block folds the edges in and out of it onto its container', () => {
  const g = big(1, 3), alt = g.nodes.find((n) => n.kind === 'alt');
  const vg = G.visibleGraph(g, new Set([alt.id]));
  assert.ok(vg.nodes.every((n) => n.id === alt.id || !n.parent));
  assert.ok(vg.edges.some((e) => e.to === 'end' && e.from === alt.id));
  assert.equal(vg.hiddenCount[alt.id], G.descendantCounts(g)[alt.id]);
});

test('the legend lists the eight colour groups', () => {
  assert.equal(G.legend().length, 8);
});

test('the markup carries no inline style attributes, so a strict content-security policy can allow it', () => {
  const result = golden('all_statements');
  const wf = workflow(result, 'Everything');
  const svg = G.svgText(wf, { agents: { Researcher: { approve: ['send'] } }, overlay: G.overlay(wf, [], ['start', 'n1']), collapsed: new Set([wf.nodes.find((n) => n.kind === 'loop').id]) });
  assert.ok(!/ style=/.test(body(svg)), 'no style attribute in the drawing');
  assert.ok(svg.includes('lg-approve'), 'the approval hand is drawn');
  assert.ok(svg.includes('lg-chip-toggle'), 'a folded block shows its toggle');
});
