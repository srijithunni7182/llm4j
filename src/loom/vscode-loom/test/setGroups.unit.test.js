'use strict';
/* Phase 21 R4 in the panel: consecutive `set` steps are one card in the Flow view; every member keeps its id, line and place. */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const M = require('../media/graph-model.js');
const V = (() => { global.document = {}; return require('../media/views.js'); })();

const golden = (name) => JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'ai-agent4j-loom', 'src', 'test', 'resources', 'graph', 'golden', name + '.json'), 'utf8'));
const wf = () => golden('parallel_lanes').workflows[0];

test('three consecutive sets are one group; the group is named by its first set', () => {
    const m = M.build(wf(), {});
    assert.deepEqual(m.by.n1.setGroup.ids, ['n1', 'n2', 'n3']);
    assert.equal(m.by.n2.inSetGroup, 'n1');
    assert.equal(m.by.n3.inSetGroup, 'n1');
    assert.equal(m.by.n1.inSetGroup, undefined);
});

test('every member keeps its own node, line and place on the main path (breakpoints, selection and the review links address it)', () => {
    const m = M.build(wf(), {});
    assert.deepEqual(m.main, ['start', 'n1', 'n2', 'n3', 'n4', 'n5', 'end']);
    ['n1', 'n2', 'n3'].forEach((id) => { assert.equal(m.by[id].kind, 'set'); assert.ok(m.by[id].line > 0, id); });
    assert.deepEqual(m.by.n2.raw.attrs, { value: '2' });
});

test('a single set, or sets split by another step, are not grouped', () => {
    const w = JSON.parse(JSON.stringify(wf()));
    w.nodes = w.nodes.filter((n) => !['n2', 'n3'].includes(n.id));
    w.edges = [{ from: 'start', to: 'n1' }, { from: 'n1', to: 'n4' }, { from: 'n4', to: 'n5' }, { from: 'n5', to: 'end' }];
    assert.equal(M.build(w, {}).by.n1.setGroup, undefined);
    const split = JSON.parse(JSON.stringify(wf()));
    split.nodes.find((n) => n.id === 'n2').kind = 'note';
    assert.equal(M.build(split, {}).by.n1.setGroup, undefined, 'n1 is alone now');
});

test('the setting turns grouping off', () => {
    const m = M.build(wf(), { groupSets: false });
    assert.equal(m.by.n1.setGroup, undefined);
    assert.equal(m.by.n2.inSetGroup, undefined);
});

test('Flow gives the group one column and moves the later steps left, so six sets do not bury the steps that matter', () => {
    const grouped = V.flowLayout(M.build(wf(), {}));
    const plain = V.flowLayout(M.build(wf(), { groupSets: false }));
    assert.equal(grouped.pos.n1.x, grouped.pos.n2.x);
    assert.equal(grouped.pos.n2.x, grouped.pos.n3.x);
    assert.ok(grouped.pos.n4.x < plain.pos.n4.x, 'the parallel block moved left');
    assert.equal(grouped.pos.n4.x - grouped.pos.n1.x, plain.pos.n2.x - plain.pos.n1.x, 'one column later, like any next step');
    assert.ok(grouped.width < plain.width);
});

test('Flow draws no line between the members of a group, only into and out of it', () => {
    const L = V.flowLayout(M.build(wf(), {}));
    const mains = L.paths.filter((p) => p.cls === 'p-main');
    const m = M.build(wf(), {});
    assert.equal(mains.length, m.main.length - 1 - 2, 'two fewer joins: n1-n2 and n2-n3');
});

test('a single set is a compact card; the group and the compact card both report which members they hold', () => {
    const w = JSON.parse(JSON.stringify(wf()));
    w.nodes = w.nodes.filter((n) => !['n2', 'n3'].includes(n.id));
    w.edges = [{ from: 'start', to: 'n1' }, { from: 'n1', to: 'n4' }, { from: 'n4', to: 'n5' }, { from: 'n5', to: 'end' }];
    const L = V.flowLayout(M.build(w, {}));
    assert.ok(L.pos.n1.h < 104 && L.pos.n1.w <= 184, 'smaller than a step card');
    const grouped = V.flowLayout(M.build(wf(), {}));
    assert.equal(grouped.pos.n1.h, L.pos.n1.h);
});

test('the sections band covers the group as one card', () => {
    const L = V.flowLayout(M.build(wf(), {}));
    assert.equal(L.bands.length, 1);
    assert.ok(L.bands[0].w > 0);
});
