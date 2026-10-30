'use strict';
/* Phase 21: a parallel block inside a branch of another, and a block with one branch, in the panel. No page needed. */
const test = require('node:test');
const assert = require('node:assert/strict');
const M = require('../media/graph-model.js');
const V = (() => { global.document = {}; return require('../media/views.js'); })();

// the graph `weave graph` gives for: a block whose second branch holds another block of two, then a block with one branch
const wf = require('./fixtures/nested_lanes.workflow.json');
const model = () => M.build(wf, {});

function overlap(a, b) { return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h; }

test('each step belongs to the innermost lane that holds it', () => {
    const m = model();
    assert.deepEqual(m.by.n4.lane, { owner: 'n1', index: 0 });
    assert.deepEqual(m.by.n5.lane, { owner: 'n1', index: 1 });
    assert.deepEqual(m.by.n7.lane, { owner: 'n5', index: 0 });
    assert.deepEqual(m.by.n8.lane, { owner: 'n5', index: 1 });
});

test('the main path runs through the outer blocks only', () => {
    const m = model();
    assert.deepEqual(m.main, ['start', 'n1', 'n2', 'n3', 'end']);
    ['n4', 'n5', 'n6', 'n7', 'n8'].forEach((id) => assert.equal(m.by[id].main, false, id));
});

test('Flow draws the nested block inside its lane, indented, and no two cards overlap', () => {
    const m = model();
    const L = V.flowLayout(m);
    const p = L.pos;
    assert.ok(p.n7.y > p.n5.y + p.n5.h - 1, 'the nested lanes are under the nested block');
    assert.ok(p.n7.x > p.n5.x, 'and indented');
    assert.ok(p.n7.y < p.n8.y);
    const ids = Object.keys(p);
    for (let i = 0; i < ids.length; i++) for (let j = i + 1; j < ids.length; j++) assert.ok(!overlap(p[ids[i]], p[ids[j]]), `${ids[i]} and ${ids[j]} overlap`);
    assert.deepEqual(L.drops.slice().sort(), ['n4', 'n5', 'n6', 'n7', 'n8']);
});

test('a block with one branch is drawn with its lane under it, like any other block', () => {
    const m = model();
    assert.equal(m.by.n2.lanes.length, 1);
    const p = V.flowLayout(m).pos;
    assert.ok(p.n6.y > p.n2.y + p.n2.h, 'the single step is under its block');
});

test('collapsing the outer block hides the nested one with it', () => {
    const L = V.flowLayout(model(), new Set(['n1']));
    ['n4', 'n5', 'n7', 'n8'].forEach((id) => assert.ok(!L.drops.includes(id), id + ' is hidden'));
});
