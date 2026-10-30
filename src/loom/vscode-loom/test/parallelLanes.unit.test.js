'use strict';
/* Phase 21 R5 in the panel: a parallel block's branches are lanes, drawn under the block between a fork and a join. No page needed. */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const M = require('../media/graph-model.js');
const V = (() => { global.document = {}; return require('../media/views.js'); })();

const golden = (name) => JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'ai-agent4j-loom', 'src', 'test', 'resources', 'graph', 'golden', name + '.json'), 'utf8'));
const lanesModel = () => M.build(golden('parallel_lanes').workflows[0], {});

function overlap(a, b) { return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h; }

test('the main path runs through the parallel block to the step after it, never through a lane', () => {
    const m = lanesModel();
    assert.deepEqual(m.main, ['start', 'n1', 'n2', 'n3', 'n4', 'n5', 'end']);
    ['n6', 'n7', 'n8', 'n9', 'n10'].forEach((id) => assert.equal(m.by[id].main, false, id));
});

test('the block knows its lanes, and each lane step knows which lane it is in', () => {
    const m = lanesModel();
    assert.deepEqual(m.by.n4.lanes.map((l) => [l.label, l.nodes]), [['A', ['n6', 'n9']], ['B', ['n7']], ['C', ['n8', 'n10']]]);
    assert.deepEqual(m.by.n9.lane, { owner: 'n4', index: 0 });
    assert.equal(m.by.n5.lane, undefined);
});

test('the fork and the join belong to the lanes: the block leads on to the next step, and a failure handler hangs from its step', () => {
    const m = lanesModel();
    assert.deepEqual(m.out.n4.map((e) => e.to), ['n5']);
    assert.deepEqual(m.out.n6.map((e) => [e.to, e.label]), [['n9', 'failure']]);
});

test('a parallel block with no lanes (an older engine) is drawn as before: one step on the main path', () => {
    const old = golden('parallel_lanes').workflows[0];
    const stripped = JSON.parse(JSON.stringify({ ...old, nodes: old.nodes.filter((n) => !n.parent), edges: [] }));
    stripped.nodes.forEach((n) => { if (n.attrs) { delete n.attrs.lanes; } });
    const ids = stripped.nodes.map((n) => n.id);
    stripped.edges = ids.slice(0, -1).map((id, i) => ({ from: id, to: ids[i + 1] }));
    const m = M.build(stripped, {});
    assert.deepEqual(m.main, ids);
    assert.equal(m.by.n4.lanes, undefined);
    const L = V.flowLayout(m);
    assert.deepEqual(L.drops, []);
});

test('Flow puts every lane under the block, stacked, with a failure handler under its step, and no two cards overlap', () => {
    const m = lanesModel();
    const L = V.flowLayout(m);
    const p = L.pos;
    const block = p.n4;
    for (const id of ['n6', 'n7', 'n8']) {
        assert.ok(p[id].y > block.y + block.h, id + ' is below the block');
        assert.ok(p[id].x >= block.x && p[id].x + p[id].w <= block.x + block.w + 22, id + ' stays in the block\'s column');
    }
    assert.ok(p.n6.y < p.n9.y && p.n9.y < p.n7.y, 'lane A: step, its handler, then lane B');
    assert.ok(p.n7.y < p.n8.y && p.n8.y < p.n10.y);
    // the members of a run of sets share one card (Phase 21 R4), so only its first set stands for it
    const cards = Object.keys(p).filter((id) => !m.by[id].inSetGroup).map((id) => p[id]);
    for (let i = 0; i < cards.length; i++) for (let j = i + 1; j < cards.length; j++) assert.ok(!overlap(cards[i], cards[j]), `cards ${i} and ${j} overlap`);
    assert.deepEqual(L.drops.slice().sort(), ['n10', 'n6', 'n7', 'n8', 'n9']);
});

test('Flow draws a fork, a join, the lane names and a dashed failure edge', () => {
    const L = V.flowLayout(lanesModel());
    const classes = L.paths.map((x) => x.cls);
    assert.ok(classes.includes('p-fork'), 'a fork bar');
    assert.ok(classes.includes('p-join'), 'a join bar');
    assert.equal(L.paths.filter((x) => x.cls.includes('p-failure')).length >= 2, true, 'a dashed edge from each step that has a failure handler');
    assert.deepEqual(L.labels.filter((l) => l.lane).map((l) => l.t), ['A', 'B', 'C']);
    assert.deepEqual(L.labels.filter((l) => l.t === 'failure').length, 2);
});

test('the stage is wide and tall enough for the lanes at any zoom', () => {
    const L = V.flowLayout(lanesModel());
    const lowest = Math.max(...Object.keys(L.pos).map((id) => L.pos[id].y + L.pos[id].h));
    const rightmost = Math.max(...Object.keys(L.pos).map((id) => L.pos[id].x + L.pos[id].w));
    assert.ok(L.height > lowest);
    assert.ok(L.width > rightmost);
});

test('a collapsed block hides its lanes', () => {
    const m = lanesModel();
    const L = V.flowLayout(m, new Set(['n4']));
    assert.deepEqual(L.drops, []);
});

test('a block with one branch still has a lane, so the renderer can draw it as a step', () => {
    const wf = golden('parallel_lanes').workflows[0];
    const one = JSON.parse(JSON.stringify(wf));
    const keep = new Set(['start', 'n1', 'n2', 'n3', 'n4', 'n5', 'end', 'n7']);
    one.nodes = one.nodes.filter((n) => keep.has(n.id));
    one.nodes.find((n) => n.id === 'n4').attrs.lanes = [{ label: 'B', nodes: ['n7'] }];
    one.edges = one.edges.filter((e) => keep.has(e.from) && keep.has(e.to));
    const m = M.build(one, {});
    assert.equal(m.by.n4.lanes.length, 1);
    assert.deepEqual(m.main, ['start', 'n1', 'n2', 'n3', 'n4', 'n5', 'end']);
});
