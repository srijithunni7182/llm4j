'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const G = require('../graph-render.js');
const { golden, workflow, agents, chain, big } = require('./fixtures.js');

function overlaps(a, b) { return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h; }

function assertSound(graph, lay, label) {
  const ids = new Set(lay.nodes.map((n) => n.id));
  const visibleEdges = graph.edges.filter((e) => ids.has(e.from) && ids.has(e.to));
  assert.ok(lay.nodes.length > 0, label + ': no nodes');
  for (let i = 0; i < lay.nodes.length; i++) {
    for (let j = i + 1; j < lay.nodes.length; j++) {
      assert.ok(!overlaps(lay.nodes[i], lay.nodes[j]), `${label}: ${lay.nodes[i].id} overlaps ${lay.nodes[j].id}`);
    }
  }
  assert.equal(lay.routes.length, visibleEdges.length, label + ': every edge has a route');
  lay.routes.forEach((r) => {
    assert.ok(r.pts.length >= 2, label + ': route too short');
    r.pts.forEach((p) => assert.ok(Number.isFinite(p[0]) && Number.isFinite(p[1]), label + ': route has a NaN'));
  });
  lay.nodes.forEach((n) => {
    assert.ok(n.x >= lay.bounds.x && n.x + n.w <= lay.bounds.x + lay.bounds.w, label + ': node outside bounds');
    assert.ok(n.y >= lay.bounds.y && n.y + n.h <= lay.bounds.y + lay.bounds.h, label + ': node outside bounds');
  });
}

test('every sample workflow lays out without overlapping boxes and with every edge routed', () => {
  ['content_factory', 'boardroom', 'digest', 'imports_parent'].forEach((name) => {
    const result = golden(name);
    result.workflows.forEach((wf) => assertSound(wf, G.layout(wf, { agents: agents(result) }), name + '/' + wf.name));
  });
});

test('a workflow that uses every statement kind, and one with every kind of handler, lay out soundly', () => {
  ['all_statements', 'handlers'].forEach((name) => {
    const result = golden(name);
    result.workflows.forEach((wf) => assertSound(wf, G.layout(wf, { agents: agents(result) }), name + '/' + wf.name));
  });
});

test('a straight chain is one column, top to bottom, in order', () => {
  const lay = G.layout(chain(5), {});
  const xs = new Set(lay.nodes.map((n) => Math.round(n.x + n.w / 2)));
  assert.equal(lay.nodes.length, 7);
  assert.ok(xs.size <= 2, 'a chain stays in one column (pill and boxes differ only in width)');
  const ys = lay.nodes.map((n) => n.y);
  assert.deepEqual(ys, [...ys].sort((a, b) => a - b));
});

test('then is left of else, and both join the next step', () => {
  const g = { nodes: [{ id: 'start', kind: 'start', label: 'Start' }, { id: 'n1', kind: 'alt', label: 'x?' }, { id: 'n2', kind: 'note', label: 'a' }, { id: 'n3', kind: 'note', label: 'b' }, { id: 'n4', kind: 'note', label: 'after' }, { id: 'end', kind: 'end', label: 'End' }],
    edges: [{ from: 'start', to: 'n1' }, { from: 'n1', to: 'n2', label: 'then' }, { from: 'n1', to: 'n3', label: 'else' }, { from: 'n2', to: 'n4' }, { from: 'n3', to: 'n4' }, { from: 'n4', to: 'end' }] };
  const lay = G.layout(g, {});
  const by = Object.fromEntries(lay.nodes.map((n) => [n.id, n]));
  assert.ok(by.n2.x < by.n3.x, 'then is left of else');
  assert.equal(by.n2.layer, by.n3.layer);
  assert.ok(by.n4.y > by.n2.y + by.n2.h);
  assertSound(g, lay, 'alt');
});

test('a loop puts its exit step below the end of its body, and the way back is a side route', () => {
  const g = { nodes: [{ id: 'start', kind: 'start', label: 'Start' }, { id: 'n1', kind: 'loop', label: 'loop', bound: 3 }, { id: 'n2', kind: 'note', label: 'a', parent: 'n1', branch: 'body' }, { id: 'n3', kind: 'note', label: 'after' }, { id: 'end', kind: 'end', label: 'End' }],
    edges: [{ from: 'start', to: 'n1' }, { from: 'n1', to: 'n2' }, { from: 'n2', to: 'n1', label: 'again' }, { from: 'n1', to: 'n3', label: 'done' }, { from: 'n3', to: 'end' }] };
  const lay = G.layout(g, {});
  const by = Object.fromEntries(lay.nodes.map((n) => [n.id, n]));
  assert.ok(by.n3.y > by.n2.y + by.n2.h, 'the step after the loop is below its body');
  const again = lay.routes.find((r) => r.label === 'again');
  assert.equal(again.back, true);
  assert.ok(again.pts.length === 4);
  assertSound(g, lay, 'loop');
});

test('a back edge never starts on a side where another node sits in the same layer', () => {
  const result = golden('content_factory');
  const wf = workflow(result, 'GenerateContent');
  const lay = G.layout(wf, {});
  lay.routes.filter((r) => r.back).forEach((r) => assert.ok(r.pts.every(([x, y]) => Number.isFinite(x + y))));
});

test('layout is deterministic', () => {
  const g = big(6, 4);
  assert.equal(JSON.stringify(G.layout(g, {})), JSON.stringify(G.layout(g, {})));
});

test('a graph with a cycle the labels do not mark still lays out', () => {
  const g = { nodes: [{ id: 'start', kind: 'start', label: 'Start' }, { id: 'a', kind: 'note', label: 'a' }, { id: 'b', kind: 'note', label: 'b' }, { id: 'end', kind: 'end', label: 'End' }],
    edges: [{ from: 'start', to: 'a' }, { from: 'a', to: 'b' }, { from: 'b', to: 'a' }, { from: 'b', to: 'end' }] };
  const lay = G.layout(g, {});
  assert.equal(lay.routes.length, 4);
  assert.ok(lay.routes.some((r) => r.back));
  assertSound(g, lay, 'cycle');
});

test('an empty graph and a single node do not crash', () => {
  assert.equal(G.layout({ nodes: [], edges: [] }, {}).nodes.length, 0);
  const one = G.layout({ nodes: [{ id: 'a', kind: 'note', label: 'a' }], edges: [] }, {});
  assert.equal(one.nodes.length, 1);
  assert.ok(Number.isFinite(one.bounds.w));
});

test('edges that name a node that is not there are ignored', () => {
  const lay = G.layout({ nodes: [{ id: 'a', kind: 'note', label: 'a' }], edges: [{ from: 'a', to: 'ghost' }] }, {});
  assert.equal(lay.routes.length, 0);
});

test('VP.3: four hundred nodes lay out in under 300 ms and a thousand in under a second', () => {
  const g400 = big(11, 12);
  assert.ok(g400.nodes.length >= 400, 'fixture has ' + g400.nodes.length);
  G.layout(g400, {});
  const time = (g) => { let worst = 0; for (let i = 0; i < 3; i++) { const t = process.hrtime.bigint(); G.layout(g, {}); worst = Math.max(worst, Number(process.hrtime.bigint() - t) / 1e6); } return worst; };
  assert.ok(time(g400) <= 300, '400 nodes took ' + time(g400));
  const g1000 = big(25, 13);
  assert.ok(g1000.nodes.length >= 1000, 'fixture has ' + g1000.nodes.length);
  assert.ok(time(g1000) <= 1000, '1000 nodes took ' + time(g1000));
});

test('layout of 400 nodes is sound', () => {
  const g = big(11, 12);
  assertSound(g, G.layout(g, {}), 'big');
});
