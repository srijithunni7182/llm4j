'use strict';
/* Phase 21 R5 in the shared renderer (Fabric, the eval report): a parallel block with lanes is a fork, one column per branch, and a join. */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const G = require('../graph-render.js');

const wf = () => JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'ai-agent4j-loom', 'src', 'test', 'resources', 'graph', 'golden', 'parallel_lanes.json'), 'utf8')).workflows[0];
const overlap = (a, b) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

test('the branches of a parallel block sit side by side below it, and the next step is below all of them', () => {
  const lay = G.layout(wf(), {});
  const at = Object.fromEntries(lay.nodes.map((n) => [n.id, n]));
  const lanes = ['n6', 'n7', 'n8'].map((id) => at[id]);
  assert.equal(new Set(lanes.map((n) => n.y)).size, 1, 'the three branches are in one row');
  assert.equal(new Set(lanes.map((n) => Math.round(n.x))).size, 3, 'in three columns');
  lanes.forEach((n) => assert.ok(n.y > at.n4.y + at.n4.h - 1, 'below the block'));
  assert.ok(at.n5.y > Math.max(...lanes.map((n) => n.y + n.h)) - 1, 'the join target is below every branch');
});

test('a failure handler sits under its own branch, on a labelled edge', () => {
  const lay = G.layout(wf(), {});
  const at = Object.fromEntries(lay.nodes.map((n) => [n.id, n]));
  assert.ok(at.n9.y > at.n6.y + at.n6.h - 1);
  assert.ok(lay.routes.some((r) => r.label === 'failure' || (r.from === 'n6' && r.to === 'n9')));
});

test('no two cards overlap', () => {
  const lay = G.layout(wf(), {});
  for (let i = 0; i < lay.nodes.length; i++) for (let j = i + 1; j < lay.nodes.length; j++) assert.ok(!overlap(lay.nodes[i], lay.nodes[j]), `${lay.nodes[i].id} and ${lay.nodes[j].id}`);
});

test('the drawing has every branch, and escapes what a script wrote', () => {
  const g = wf();
  g.nodes.find((n) => n.id === 'n6').label = 'delegate <img src=x onerror=alert(1)>';
  const svg = G.svgText(g, {});
  for (const id of ['n6', 'n7', 'n8', 'n9', 'n10']) assert.ok(svg.includes('data-id="' + id + '"'), id);
  assert.ok(!svg.includes('<img'));
});

test('a graph without lanes (an older engine) lays out exactly as before', () => {
  const g = wf();
  g.nodes = g.nodes.filter((n) => !n.parent);
  g.nodes.forEach((n) => { if (n.attrs) { delete n.attrs.lanes; } });
  const ids = g.nodes.map((n) => n.id);
  g.edges = ids.slice(0, -1).map((id, i) => ({ from: id, to: ids[i + 1] }));
  const lay = G.layout(g, { groupSets: false });
  assert.equal(lay.nodes.length, ids.length);
  assert.equal(new Set(lay.nodes.map((n) => Math.round(n.x + n.w / 2))).size, 1, 'one straight column');
});

/* ---- Phase 21 R4: runs of consecutive sets are one node ---- */

test('three consecutive sets are drawn as one node that names its members', () => {
  const lay = G.layout(wf(), {});
  const ids = lay.nodes.map((n) => n.id);
  assert.ok(ids.includes('n1') && !ids.includes('n2') && !ids.includes('n3'));
  const head = lay.nodes.find((n) => n.id === 'n1');
  assert.deepEqual(head.members, ['n1', 'n2', 'n3']);
  assert.equal(G.title(head.node), 'sets 3 variables');
  assert.equal(head.sub, 'a, b, c');
});

test('the group has the edges the sets had: into the first and out of the last', () => {
  const lay = G.layout(wf(), {});
  const at = Object.fromEntries(lay.nodes.map((n) => [n.id, n]));
  assert.ok(at.n4.y > at.n1.y, 'the parallel block follows the group');
  assert.equal(lay.nodes.length, wf().nodes.length - 2);
});

test('the drawing says which steps a group holds, so the host can address each of them', () => {
  const svg = G.svgText(wf(), {});
  assert.match(svg, /data-id="n1"[^>]*data-members="n1 n2 n3"/);
  assert.ok(!svg.includes('data-id="n2"'));
});

test('grouping can be turned off, and a lone set is never grouped', () => {
  const off = G.layout(wf(), { groupSets: false });
  assert.equal(off.nodes.length, wf().nodes.length);
  const g = wf();
  g.nodes = g.nodes.filter((n) => !['n2', 'n3'].includes(n.id));
  g.edges = [{ from: 'start', to: 'n1' }, { from: 'n1', to: 'n4' }, { from: 'n4', to: 'n6' }, { from: 'n4', to: 'n7' }, { from: 'n4', to: 'n8' }, { from: 'n6', to: 'n5' }, { from: 'n7', to: 'n5' }, { from: 'n8', to: 'n5' }, { from: 'n5', to: 'end' }];
  g.nodes = g.nodes.filter((n) => !['n9', 'n10'].includes(n.id));
  const lone = G.layout(g, {});
  assert.equal(lone.nodes.find((n) => n.id === 'n1').members, undefined);
});

test('sets that something branches out of or into are not folded', () => {
  const g = { nodes: [{ id: 'start', kind: 'start', label: 'Start' }, { id: 'a', kind: 'set', label: 'set a' }, { id: 'b', kind: 'set', label: 'set b' }, { id: 'c', kind: 'note', label: 'note' }, { id: 'end', kind: 'end', label: 'End' }],
    edges: [{ from: 'start', to: 'a' }, { from: 'a', to: 'b' }, { from: 'a', to: 'c' }, { from: 'b', to: 'end' }, { from: 'c', to: 'end' }] };
  assert.equal(G.layout(g, {}).nodes.length, 5);
});
